package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #77「确定性最小复现」的墓碑，走 [CarCommandRouter.logTeardownDisplaySnapshot]
 * 的 `[teardown-display] residualTasks=` 输出 —— 那是 #48 判断「哪些任务没被搬回物理屏」的唯一依据。
 *
 * 文件名带 `RedFirst` 是因为它**刻意只依赖 base 已存在的生产代码**：把本文件拷进 pristine base
 * （`git archive main`）即可编译并真实失败，失败签名与 issue 症状逐字一致：
 *   display #0 → residualTasks=[1,1,1]              （期望 [1]）
 *   display #9 → residualTasks=[178,178,178,345,345]（期望 [178]）
 * 打补丁后同文件转绿。期望值全部是字面量，不由被测函数反推。
 */
class Issue77RedFirstTest {

    /**
     * 合成 dump（issue #77「确定性最小复现」，`aaa111/bbb222/ccc333` 为现场标记）。
     *
     * `Display #9` 段内只有引用行（`topDisplayFocusedRootTask=Task{…#178 …}`）与一条
     * `ResumedActivity: ActivityRecord{… t178}`；段后紧跟**物理屏**记述
     * `Display: mDisplayId=0 (organized)` 与其 task `#345` —— 修复前会被一路吞进 #9 的切片，
     * 于是 `345` 混进 VD 的残留任务列表（跨屏泄漏）。
     */
    private val syntheticDump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{aaa111 #1 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
            rootOfTask=true task=Task{aaa111 #1 type=home}
            topDisplayFocusedRootTask=Task{aaa111 #1 type=home}
        Display #9 (activities from top to bottom):
          ResumedActivity: ActivityRecord{22 u0 com.example/.Main t178}
          ActivityTaskSupervisor state: topDisplayFocusedRootTask=Task{bbb222 #178 type=home}
          topDisplayFocusedRootTask=Task{bbb222 #178 type=home}
          PreferredTopFocusableRootTask=Task{bbb222 #178 type=home}
          Display: mDisplayId=0 (organized) init=1368x3192 560dpi
            * Task{ccc333 #345 type=standard A=10334:moe.shizuku}
              rootOfTask=true task=Task{ccc333 #345 type=standard A=10334:moe.shizuku}
    """.trimIndent()

    private class Harness(val activitiesDump: String, val displayId: Int) {
        val router = CarCommandRouter(
            exec = { },
            execOut = { cmd ->
                when {
                    cmd.startsWith("dumpsys activity activities") -> activitiesDump
                    cmd.startsWith("dumpsys window displays") ->
                        "Display: mDisplayId=0\n  init=1368x3192 560dpi cur=1368x3192\n"
                    else -> null
                }
            },
            displayId = { displayId },
            onStackEmpty = { },
            setDisplayPower = { },
            requestSyncFrame = { },
            repairLayerTree = { },
        )
    }

    private fun snapshotLog(displayId: Int): String {
        val h = Harness(syntheticDump, displayId)
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true))
        try {
            h.router.logTeardownDisplaySnapshot(displayId)
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    /** display #0：issue 期望 `residualTasks=[1]`（修复前 `[1,1,1]`）。 */
    @Test
    fun `issue 77 display zero reports each task once`() {
        assertTrue(
            "display#0 的残留任务必须每个只出现一次",
            snapshotLog(0).contains("residualTasks=[1]")
        )
    }

    /** display #9：issue 期望 `residualTasks=[178]`（修复前 `[178,178,178,345,345]`）。 */
    @Test
    fun `issue 77 display nine reports each task once and excludes the physical screen task`() {
        val log = snapshotLog(9)

        assertTrue("VD 的残留任务必须每个只出现一次", log.contains("residualTasks=[178]"))
        assertTrue("物理屏的 task 345 不得混进 VD 的残留列表", !log.contains("345"))
    }

    /** 同一 dump 下残留列表没有重复 id（`emitted == distinct` 的墓碑）。 */
    @Test
    fun `issue 77 residual task list is duplicate free`() {
        val raw = Regex("residualTasks=\\[([^\\]]*)\\]").find(snapshotLog(9))!!.groupValues[1]
        val ids = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        assertEquals(listOf("178"), ids)
    }
}
