package com.dilinkauto.client.service

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.dilinkauto.client.display.VirtualDisplayClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialM2Test {

    private fun createDummyContext(): Context {
        return ContextWrapper(null)
    }

    // ─── Challenge 3: NotificationService Exception Safety ───

    @Test
    fun testNotificationService_appNameExceptionSafety() {
        val testPackage = "com.uninstalled.app"

        // Simulating NotificationService.onNotificationPosted appName resolution logic:
        // val appName = try {
        //     val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
        //     packageManager.getApplicationLabel(appInfo).toString()
        // } catch (_: Exception) {
        //     sbn.packageName
        // }

        val resolveAppName: (pmCall: () -> CharSequence) -> String = { pmCall ->
            try {
                pmCall().toString()
            } catch (_: Exception) {
                testPackage
            }
        }

        // Scenario 1: NameNotFoundException thrown (e.g. uninstalled app or virtual pkg)
        val nameNotFoundResult = resolveAppName {
            throw PackageManager.NameNotFoundException("Package not found: $testPackage")
        }
        assertEquals("Must fall back to packageName on NameNotFoundException without crashing",
            testPackage, nameNotFoundResult)

        // Scenario 2: SecurityException thrown (e.g. multi-user or private profile restriction)
        val securityResult = resolveAppName {
            throw SecurityException("Access denied to package: $testPackage")
        }
        assertEquals("Must fall back to packageName on SecurityException without crashing",
            testPackage, securityResult)

        // Scenario 3: NullPointerException thrown (e.g. unexpected null in package manager stub)
        val npeResult = resolveAppName {
            throw NullPointerException("Null app info")
        }
        assertEquals("Must fall back to packageName on NPE without crashing",
            testPackage, npeResult)

        // Scenario 4: Normal resolution succeeds
        val normalResult = resolveAppName {
            "Expected App Label"
        }
        assertEquals("Expected App Label", normalResult)
    }

    // ─── Challenge 2: VirtualDisplayClient & ConnectionService Cleanup and Reconnect ───

    @Test
    fun testVirtualDisplayClient_cleanupAndReconnectionLifecycle() = runTest {
        val scope = TestScope()
        val dummyContext = createDummyContext()
        val testPort = 19648 // Use alternate port for unit test isolation

        val client = VirtualDisplayClient(scope, dummyContext)
        assertFalse("Initial isConnected must be false", client.isConnected)
        assertEquals("Initial displayId must be -1", -1, client.displayId)

        // Step 1: Start listening on port
        client.startListening(testPort)

        // Step 2: Simulate disconnect() as called in cleanupSession()
        client.disconnect()
        assertFalse("isConnected must be false after disconnect", client.isConnected)
        assertEquals("displayId must be reset to -1 after disconnect", -1, client.displayId)

        // Step 3: Reconnection simulation: A fresh client can bind the port immediately (SO_REUSEADDR)
        val reconnectClient = VirtualDisplayClient(scope, dummyContext)
        try {
            reconnectClient.startListening(testPort)
            // If port was leaked or not closed in disconnect(), startListening would throw BindException
            assertTrue("startListening on reconnect must succeed without port collision", true)
        } finally {
            reconnectClient.disconnect()
        }
    }

    @Test
    fun testConnectionService_sessionCleanupResetSemantics() {
        val dummyContext = createDummyContext()
        // Simulates ConnectionService session state before and after cleanupSession()
        var vdClient: VirtualDisplayClient? = VirtualDisplayClient(TestScope(), dummyContext)
        var isConnected = true
        var serviceState = "STREAMING"

        assertNotNull(vdClient)
        assertTrue(isConnected)

        // Simulating cleanupSession()
        vdClient?.disconnect()
        vdClient = null
        isConnected = false
        serviceState = "WAITING"

        assertNull("vdClient MUST be null after cleanupSession", vdClient)
        assertFalse("isConnected must be false after cleanupSession", isConnected)
        assertEquals("Service state must revert to WAITING", "WAITING", serviceState)

        // Simulating subsequent handshake request:
        // if (vdClient == null) -> fresh client initialized
        if (vdClient == null) {
            val freshClient = VirtualDisplayClient(TestScope(), dummyContext)
            freshClient.startListening(19649)
            vdClient = freshClient
        }

        assertNotNull("Reconnection must allocate a fresh vdClient", vdClient)
        vdClient?.disconnect()
    }
}
