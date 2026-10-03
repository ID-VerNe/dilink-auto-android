package com.dilinkauto.client.ui

import java.net.Inet4Address
import java.net.NetworkInterface

/** Network helpers for the phone UI (status card shows local IPs on port 9637). */
internal fun getLocalIpAddresses(): List<String> {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && it is Inet4Address }
                    .map { "${it.hostAddress}:9637" }
            }
    } catch (_: Exception) {
        emptyList()
    }
}
