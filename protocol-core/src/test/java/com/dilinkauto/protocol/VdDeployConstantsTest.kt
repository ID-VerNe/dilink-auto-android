package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VdDeploy 常量与命令串锁定测试。
 * （commandLine / shellQuote / buildDeployPlan 已由 VdDeployCommandLineTest 覆盖。）
 *
 * kill/stop/probe 里的 [P]ipelineServer 括号技巧"防 pkill -f 误杀包裹 shell 自身"，
 * stopCommand 的两段式（先 SIGTERM 让 JVM shutdown hook 跑 VirtualDisplay 清理）是防黑屏的关键，
 * 一次"顺手清理正则/改停止路径"就会悄悄重新引入静默失败，必须逐字锁死。
 */
class VdDeployConstantsTest {

    @Test
    fun `process pattern and main class are pinned`() {
        assertEquals("[P]ipelineServer", VdDeploy.PROCESS_PATTERN)
        assertEquals("com.dilinkauto.vdserver.PipelineServer", VdDeploy.MAIN_CLASS)
    }

    @Test
    fun `paths are the documented constants`() {
        assertEquals("/sdcard/DiLinkAuto/vd-server.jar", VdDeploy.JAR_PATH)
        assertEquals("/sdcard/DiLinkAuto/vd-server.log", VdDeploy.LOG_PATH)
    }

    @Test
    fun `kill commands use the bracket trick not the literal name`() {
        assertEquals("pkill -f [P]ipelineServer 2>/dev/null", VdDeploy.killCommand)
        assertTrue(VdDeploy.killCommand.contains("[P]ipelineServer"))
        // 不能出现裸名（会匹配到包裹 shell 自身的 cmdline）
        assertFalse(VdDeploy.killCommand.contains("PipelineServer") && !VdDeploy.killCommand.contains("[P]"))
    }

    @Test
    fun `kill command force uses sigkill`() {
        assertEquals("pkill -9 -f [P]ipelineServer 2>/dev/null", VdDeploy.killCommandForce)
    }

    @Test
    fun `stop command is two stage with sleep and exit zero`() {
        assertEquals(
            "pkill -f [P]ipelineServer 2>/dev/null; sleep 1; pkill -9 -f [P]ipelineServer 2>/dev/null; exit 0",
            VdDeploy.stopCommand
        )
        assertTrue(VdDeploy.stopCommand.contains("sleep 1"))
        assertTrue(VdDeploy.stopCommand.contains("pkill -9 -f"))
        assertTrue(VdDeploy.stopCommand.endsWith("exit 0"))
    }

    @Test
    fun `probe command prints Y and N not exit codes`() {
        assertTrue(VdDeploy.probeCommand.contains("echo Y"))
        assertTrue(VdDeploy.probeCommand.contains("echo N"))
        assertTrue(VdDeploy.probeCommand.contains("pkill -0 -f"))
    }

    @Test
    fun `probe exit code command uses exit zero one and no echo`() {
        assertTrue(VdDeploy.probeExitCodeCommand.contains("exit 0"))
        assertTrue(VdDeploy.probeExitCodeCommand.contains("exit 1"))
        assertFalse(VdDeploy.probeExitCodeCommand.contains("echo"))
    }
}
