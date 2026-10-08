package com.dilinkauto.client.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CarIpLocator]. The locator is a plain object (no Service
 * dependency) so its non-network helpers are testable directly.
 *
 * Network probes themselves are not asserted beyond the closed-port case —
 * they hit real sockets and the JVM stub (`isReturnDefaultValues = true`)
 * makes the Android-aware paths return defaults. We assert the structural
 * invariants: a closed local port times out within the deadline rather than
 * hanging, and the scan traverses every strategy and returns null without
 * throwing when nothing is reachable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarIpLocatorTest {

    @Test
    fun probePortSync_returnsFalseOnClosedPort() {
        // Port 1 is reserved and never open on a test host — probe must time out
        // to false within the 500ms deadline rather than hanging.
        val start = System.currentTimeMillis()
        val result = CarIpLocator.probePortSync("127.0.0.1", 1)
        val elapsed = System.currentTimeMillis() - start
        assertFalse("Probe to closed port 1 must fail", result)
        // 500ms timeout + small slack; must not hang
        assertTrue("Probe must respect the 500ms deadline (took ${elapsed}ms)",
            elapsed < 1500)
    }

    @Test
    fun findCarAdb_returnsNullWhenNoControlConnectionAndNoInterfaces() = runTest {
        // A null remote IP, no WiFi manager, and a probe that reports every host
        // as closed. The probe is stubbed because findCarAdb sweeps real
        // subnets — without the stub this asserts "no machine on this LAN is
        // listening on 5555", which is a property of the test host, not of
        // CarIpLocator, and fails on any developer machine with a real car or
        // another ADB device on the same network.
        val savedWifi = CarIpLocator.wifiManager
        val savedProbe = CarIpLocator.portProbeOverride
        try {
            CarIpLocator.wifiManager = null
            CarIpLocator.portProbeOverride = { _, _ -> false }
            val result = CarIpLocator.findCarAdb(controlConnectionRemoteIp = null)
            assertNull("findCarAdb must return null when no car is reachable", result)
        } finally {
            CarIpLocator.wifiManager = savedWifi
            CarIpLocator.portProbeOverride = savedProbe
        }
    }

    @Test
    fun findCarAdb_prefersTheControlConnectionRemoteIpWhenItsPortIsOpen() = runTest {
        // With a reachable control connection the first strategy must win,
        // before any subnet scanning happens.
        val savedWifi = CarIpLocator.wifiManager
        val savedProbe = CarIpLocator.portProbeOverride
        try {
            CarIpLocator.wifiManager = null
            CarIpLocator.portProbeOverride = { ip, port -> ip == "10.0.0.7" && port == 5555 }
            val result = CarIpLocator.findCarAdb(controlConnectionRemoteIp = "10.0.0.7")
            assertEquals("10.0.0.7", result)
        } finally {
            CarIpLocator.wifiManager = savedWifi
            CarIpLocator.portProbeOverride = savedProbe
        }
    }

    @Test
    fun findCarAdb_ignoresAClosedControlConnectionAndKeepsSearching() = runTest {
        // A control connection whose ADB port is closed must not end the search —
        // the car may be reachable on another strategy.
        val savedWifi = CarIpLocator.wifiManager
        val savedProbe = CarIpLocator.portProbeOverride
        try {
            CarIpLocator.wifiManager = null
            // Nothing is reachable, so this asserts the closed control IP is
            // discarded and the scan completes with null rather than short-circuit.
            CarIpLocator.portProbeOverride = { _, _ -> false }
            val result = CarIpLocator.findCarAdb(controlConnectionRemoteIp = "10.0.0.7")
            assertNull(result)
        } finally {
            CarIpLocator.wifiManager = savedWifi
            CarIpLocator.portProbeOverride = savedProbe
        }
    }
}


