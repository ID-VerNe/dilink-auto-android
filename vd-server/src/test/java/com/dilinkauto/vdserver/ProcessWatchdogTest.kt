package com.dilinkauto.vdserver

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProcessWatchdogTest {

    private val dir: File = Files.createTempDirectory("watchdog-test").toFile()
    private val stopPath: String = File(dir, "stop-file").absolutePath

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun stopRequestTriggersOnStopRequested() {
        File(stopPath).writeText("")
        val running = AtomicBoolean(true)
        val cleanedUp = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val forceCleanupCalled = AtomicBoolean(false)

        val watchdog = ProcessWatchdog(
            isRunning = { running.get() },
            isCleanedUp = { cleanedUp.get() },
            onStopRequested = {
                stopRequested.set(true)
                running.set(false)
                cleanedUp.set(true)
            },
            onForceCleanup = { forceCleanupCalled.set(true) },
            stopRequestPath = stopPath,
            gracePeriodMs = 50,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            sleep = { Thread.sleep(it) }
        )

        watchdog.runLoop()

        assertTrue(stopRequested.get())
        assertFalse(forceCleanupCalled.get())
    }

    @Test
    fun normalTeardownDoesNotTriggerForceCleanup() {
        val running = AtomicBoolean(false)
        val cleanedUp = AtomicBoolean(true)
        val forceCleanupCalled = AtomicBoolean(false)

        val watchdog = ProcessWatchdog(
            isRunning = { running.get() },
            isCleanedUp = { cleanedUp.get() },
            onStopRequested = {},
            onForceCleanup = { forceCleanupCalled.set(true) },
            stopRequestPath = stopPath,
            gracePeriodMs = 50,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            sleep = { Thread.sleep(it) }
        )

        watchdog.runLoop()

        assertFalse(forceCleanupCalled.get())
    }

    @Test
    fun timeoutTriggersForceCleanupAndExit() {
        val running = AtomicBoolean(false)
        val cleanedUp = AtomicBoolean(false)
        val forceCleanupCalled = AtomicBoolean(false)
        val exitCode = AtomicInteger(-1)

        val watchdog = ProcessWatchdog(
            isRunning = { running.get() },
            isCleanedUp = { cleanedUp.get() },
            onStopRequested = {},
            onForceCleanup = { forceCleanupCalled.set(true) },
            stopRequestPath = stopPath,
            gracePeriodMs = 30,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            exitProcess = { exitCode.set(it) },
            sleep = { /* no-op in test for speed */ }
        )

        watchdog.runLoop()

        assertTrue(forceCleanupCalled.get())
        assertEquals(1, exitCode.get())
    }

    @Test
    fun forceCleanupExceptionIsCaughtAndExitStillProceeds() {
        val running = AtomicBoolean(false)
        val cleanedUp = AtomicBoolean(false)
        val exitCode = AtomicInteger(-1)
        val errLogged = AtomicBoolean(false)

        val watchdog = ProcessWatchdog(
            isRunning = { running.get() },
            isCleanedUp = { cleanedUp.get() },
            onStopRequested = {},
            onForceCleanup = { throw RuntimeException("forced cleanup failed") },
            stopRequestPath = stopPath,
            gracePeriodMs = 20,
            pollIntervalMs = 10,
            cleanupCheckIntervalMs = 10,
            err = { errLogged.set(true) },
            exitProcess = { exitCode.set(it) },
            sleep = { /* no-op in test */ }
        )

        watchdog.runLoop()

        assertTrue(errLogged.get())
        assertEquals(1, exitCode.get())
    }
}
