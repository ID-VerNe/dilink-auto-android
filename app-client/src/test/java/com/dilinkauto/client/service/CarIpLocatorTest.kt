package com.dilinkauto.client.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
        // With a null remote IP and no WiFi manager wired, the locator must
        // traverse every strategy and return null without throwing.
        val saved = CarIpLocator.wifiManager
        try {
            CarIpLocator.wifiManager = null
            val result = CarIpLocator.findCarAdb(controlConnectionRemoteIp = null)
            assertNull("findCarAdb must return null when no car is reachable", result)
        } finally {
            CarIpLocator.wifiManager = saved
        }
    }
}


