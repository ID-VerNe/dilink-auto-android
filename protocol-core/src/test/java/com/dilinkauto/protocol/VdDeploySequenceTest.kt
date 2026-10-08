package com.dilinkauto.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [vdRunDeploySequence] / [vdAwaitExit] convergence and ordering tests.
 *
 * Uses a fake executor (records commands, scripted probe results) plus a fake
 * clock so the 3s wait budget is spent instantly. The transport-level wrappers
 * are covered by `AdbDeployerTest` (desktop) against the same sequence.
 */
class VdDeploySequenceTest {

    private class FakeClock {
        var millis = 0L
        fun now(): Long = millis
        fun sleep(ms: Long) {
            millis += ms
        }
    }

    /** Records every command; probe results come from the scripted queue (default: GONE). */
    private class FakeExecutor(
        private val probes: ArrayDeque<VdProbeResult> = ArrayDeque(),
        private val launchResult: Boolean = true,
    ) : VdDeployExecutor {
        val commands = mutableListOf<String>()
        var probeCount = 0
            private set

        override suspend fun shellSync(command: String) {
            commands += command
        }

        override suspend fun launch(command: String): Boolean {
            commands += command
            return launchResult
        }

        override suspend fun probe(): VdProbeResult {
            probeCount++
            return probes.removeFirstOrNull() ?: VdProbeResult.GONE
        }
    }

    private fun plan() = VdDeploy.DeployPlan(
        args = "dummy-args",
        killCommand = VdDeploy.killCommand,
        launchCommand = "launch-command",
    )

    private fun run(executor: FakeExecutor, clock: FakeClock = FakeClock()): VdDeployOutcome =
        runBlocking {
            vdRunDeploySequence(plan(), executor, now = clock::now, sleep = { clock.sleep(it) })
        }

    @Test
    fun `clean exit runs kill, two gone probes, then launch`() {
        val executor = FakeExecutor(ArrayDeque(listOf(VdProbeResult.GONE, VdProbeResult.GONE)))

        val outcome = run(executor)

        assertEquals(listOf(VdDeploy.killCommand, "launch-command"), executor.commands)
        assertEquals("连续两次 GONE 确认退出", 2, executor.probeCount)
        assertFalse("正常退出不应触发兜底强杀", outcome.forcedKill)
        assertTrue(outcome.launched)
    }

    @Test
    fun `alive probe resets the consecutive-gone counter`() {
        val executor = FakeExecutor(
            ArrayDeque(listOf(VdProbeResult.ALIVE, VdProbeResult.GONE, VdProbeResult.GONE))
        )

        val outcome = run(executor)

        assertEquals("存活一次后需再确认两次", 3, executor.probeCount)
        assertFalse(outcome.forcedKill)
        assertEquals(listOf(VdDeploy.killCommand, "launch-command"), executor.commands)
    }

    @Test
    fun `timeout falls back to stopCommand and still launches`() {
        // Never gone → both waits time out; the second must still end with launch.
        val commands = mutableListOf<String>()
        val stubborn = object : VdDeployExecutor {
            override suspend fun shellSync(command: String) {
                commands += command
            }

            override suspend fun launch(command: String): Boolean {
                commands += command
                return true
            }

            override suspend fun probe(): VdProbeResult = VdProbeResult.ALIVE
        }
        val clock = FakeClock()
        val outcome = runBlocking {
            vdRunDeploySequence(plan(), stubborn, now = clock::now, sleep = { clock.sleep(it) })
        }

        assertTrue("超时后必须补 stopCommand", outcome.forcedKill)
        assertEquals("假时钟两轮等待各耗尽 3s 预算", 6_000L, clock.millis)
        assertEquals(
            listOf(VdDeploy.killCommand, VdDeploy.stopCommand, "launch-command"),
            commands,
        )
    }

    @Test
    fun `unknown probe accepts the exit immediately`() {
        val executor = FakeExecutor(ArrayDeque(listOf(VdProbeResult.UNKNOWN)))

        val outcome = run(executor)

        assertEquals("无法探测时立即放行，不轮询", 1, executor.probeCount)
        assertFalse(outcome.forcedKill)
        assertEquals(listOf(VdDeploy.killCommand, "launch-command"), executor.commands)
    }

    @Test
    fun `launch failure is propagated without force-kill`() {
        val executor = FakeExecutor(
            ArrayDeque(listOf(VdProbeResult.GONE, VdProbeResult.GONE)),
            launchResult = false,
        )

        val outcome = run(executor)

        assertFalse(outcome.launched)
        assertFalse(outcome.forcedKill)
    }
}
