package com.dilinkauto.client.service

import android.net.wifi.WifiManager
import com.dilinkauto.client.FileLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.channels.SocketChannel

/**
 * Locates the car's ADB-over-WiFi service (port 5555) on the local network.
 *
 * Strategies, in order of latency/cost:
 *  1. The control TCP connection's remote address (free — car is already connected).
 *  2. Subnet enumeration (so later strategies can skip own IPs).
 *  3. ARP table (`/proc/net/arp`).
 *  4. Neighbor cache (`ip neigh`).
 *  5. Parallel /24 scan (slowest — full 1-254 probe per prefix).
 *  6. WiFi gateway.
 *
 * Extracted from [ConnectionService] (Phase K3 / code-audit 5.3) so the scan
 * logic is unit-testable without a `Service` and the connection-lifecycle code
 * stays small.
 */
object CarIpLocator {

    private const val TAG = "CarIpLocator"
    private const val CAR_ADB_PORT = 5555

    /** Returns the car's IPv4 address, or null if no ADB endpoint was found. */
    suspend fun findCarAdb(controlConnectionRemoteIp: String?): String? {
        // 1. Check the control connection's remote address (car is already connected)
        controlConnectionRemoteIp?.let { ip ->
            if (probePort(ip, CAR_ADB_PORT)) {
                FileLog.i(TAG, "Found car ADB at $ip (control connection)")
                return ip
            }
        }

        // 2. Scan ALL local subnets (phone may be on both home WiFi + hotspot)
        val subnetIps = getLocalSubnetIps()
        val prefixes = subnetIps.map { it.substringBeforeLast(".") }.distinct()
        FileLog.d(TAG, "Local subnets: $subnetIps (prefixes: $prefixes)")

        // 3. ARP table (may be blocked on Android 14+)
        try {
            for (line in java.io.File("/proc/net/arp").readLines().drop(1)) {
                val ip = line.split("\\s+".toRegex()).firstOrNull() ?: continue
                if (ip == "0.0.0.0") continue
                if (subnetIps.contains(ip)) continue
                if (probePort(ip, CAR_ADB_PORT)) {
                    FileLog.i(TAG, "Found car ADB at $ip (ARP)")
                    return ip
                }
            }
        } catch (e: Exception) {
            FileLog.d(TAG, "ARP not available: ${e.message}")
        }

        // 4. Neighbor cache
        val neighProcess = try {
            Runtime.getRuntime().exec(arrayOf("ip", "neigh"))
        } catch (_: Exception) { null }
        if (neighProcess != null) {
            try {
                val out = neighProcess.inputStream.bufferedReader().readText()
                for (line in out.lines()) {
                    val ip = line.split("\\s+".toRegex()).firstOrNull() ?: continue
                    if (!ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) continue
                    if (subnetIps.contains(ip)) continue
                    if (probePort(ip, CAR_ADB_PORT)) {
                        FileLog.i(TAG, "Found car ADB at $ip (neighbor)")
                        return ip
                    }
                }
            } catch (_: Exception) {} finally {
                // Always reap the process — a leaked `ip` child pins a PID and an
                // FD pair until GC, and on a reconnect loop we'd accumulate dozens.
                neighProcess.inputStream.close()
                neighProcess.errorStream.close()
                neighProcess.outputStream.close()
                neighProcess.destroy()
            }
        }

        // 5. Parallel scan on ALL subnets
        for (prefix in prefixes) {
            FileLog.i(TAG, "Scanning $prefix.0/24 for ADB...")
            val startMs = System.currentTimeMillis()
            val result = probeSubnetConcurrent(prefix, ownIps = subnetIps, maxConcurrent = 32)
            val elapsed = System.currentTimeMillis() - startMs
            if (result != null) {
                FileLog.i(TAG, "Found car ADB at $result ($prefix.0/24, ${elapsed}ms)")
                return result
            }
            FileLog.d(TAG, "$prefix.0/24: no ADB found (${elapsed}ms)")
        }

        // 6. Gateway
        try {
            @Suppress("DEPRECATION")
            val wm = wifiManager ?: return null
            val gw = wm.dhcpInfo.gateway
            if (gw != 0) {
                val ip = String.format("%d.%d.%d.%d",
                    gw and 0xFF, (gw shr 8) and 0xFF,
                    (gw shr 16) and 0xFF, (gw shr 24) and 0xFF)
                if (!subnetIps.contains(ip) && probePort(ip, CAR_ADB_PORT)) {
                    FileLog.i(TAG, "Found car ADB at $ip (gateway)")
                    return ip
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /** WiFi manager — set by [ConnectionService] at startup (avoids a static Service reference). */
    @Volatile
    var wifiManager: WifiManager? = null

    private fun getLocalSubnetIps(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filter { it is Inet4Address && !it.isLoopbackAddress }
                        .map { it.hostAddress!! }
                }
                .filter { !it.startsWith("127.") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Scans a /24 subnet for the ADB port using parallel concurrent probes.
     * Probes the full 1-254 range in batches of [maxConcurrent], 150ms timeout each.
     */
    private suspend fun probeSubnetConcurrent(
        prefix: String, ownIps: List<String>, maxConcurrent: Int = 32
    ): String? = coroutineScope {
        // Skip .0 (network) and .255 (broadcast), and own IPs
        val ownIpSet = ownIps.toSet()
        val ips = (1..254).map { "$prefix.$it" }.filter { it !in ownIpSet }
        ips.chunked(maxConcurrent).forEach { batch ->
            val results = batch.map { ip ->
                async(Dispatchers.IO) { if (probePortRaw(ip, CAR_ADB_PORT)) ip else null }
            }
            results.forEach { deferred ->
                val found = deferred.await()
                if (found != null) {
                    coroutineContext.cancelChildren() // cancel remaining probes
                    return@coroutineScope found
                }
            }
        }
        null
    }

    /** Non-suspend port probe (150ms timeout) for use in parallel scans. */
    private fun probePortRaw(ip: String, port: Int): Boolean {
        return try {
            val ch = SocketChannel.open()
            ch.configureBlocking(false)
            ch.connect(InetSocketAddress(ip, port))
            val deadline = System.currentTimeMillis() + 150
            try {
                while (!ch.finishConnect()) {
                    if (System.currentTimeMillis() > deadline) return false
                    Thread.sleep(5)
                }
                true
            } finally {
                ch.close()
            }
        } catch (_: Exception) { false }
    }

    /** Synchronous port probe with a 500ms timeout — used by the manual install path. */
    fun probePortSync(ip: String, port: Int): Boolean {
        return try {
            val ch = SocketChannel.open()
            ch.configureBlocking(false)
            ch.connect(InetSocketAddress(ip, port))
            val deadline = System.currentTimeMillis() + 500
            try {
                while (!ch.finishConnect()) {
                    if (System.currentTimeMillis() > deadline) return false
                    Thread.sleep(5)
                }
                true
            } finally {
                ch.close()
            }
        } catch (_: Exception) { false }
    }

    private suspend fun probePort(ip: String, port: Int): Boolean {
        return try {
            val ch = SocketChannel.open()
            ch.configureBlocking(false)
            ch.connect(InetSocketAddress(ip, port))
            val deadline = System.currentTimeMillis() + 500
            try {
                while (!ch.finishConnect()) {
                    if (System.currentTimeMillis() > deadline) return false
                    delay(50)
                }
                true
            } finally {
                ch.close()
            }
        } catch (_: Exception) { false }
    }
}
