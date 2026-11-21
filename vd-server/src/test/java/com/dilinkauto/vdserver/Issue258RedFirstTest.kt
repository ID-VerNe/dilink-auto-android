package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * issue #258 / #220 的回归钉子（纯 JVM 假 shell，不依赖设备）。
 *
 * 现场（2026-11-20 08:35，`main @ 2b3d8a34`）：
 *  - `[teardown-move-all] from=43 to=0 tasks=[929, 940, 941]` 里 #940 是 #17 从物理屏
 *    迁进 VD 的 home root task，物理屏已由系统重建 #942；搬回去必报
 *    `addRootTaskReferenceIfNeeded: root home task=…#942 already exist … rootTask=…#940`；
 *  - #929 是 `moveTopApp` 刚搬走的 task，旧快照里仍带着它，第二次 `move-stack` 必报
 *    `Trying to move rootTask … to its current taskDisplayArea`（issue #220 同型）。
 *
 * 基线：`home root task …` 这条在未打补丁的 base 上红在 `moved.none { 940 }` 断言
 * （base 会发 `am display move-stack 940 0`）。
 */
class Issue258RedFirstTest {

    /**
     * 实机形态的 dump：Display #0 是物理屏（系统重建的 home #942），Display #43 是 VD，
     * 段内同时有普通任务（#929、#941）与 `type=home` 的 #940。
     */
    private val dump = """
Display #0 (activities from top to bottom):
  * Task{phy1 #942 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
    * ActivityRecord{aa1 u0 com.android.launcher3/.uioverrides.QuickstepLauncher t942}
Display #43 (activities from top to bottom):
  * Task{a11 #929 type=standard A=10216:com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen}
    * ActivityRecord{bb1 u0 com.autonavi.minimap/com.autonavi.map.activity.SplashActivity t929}
  * Task{h99 #940 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
    * ActivityRecord{cc1 u0 com.android.launcher3/.uioverrides.QuickstepLauncher t940}
  * Task{b22 #941 type=standard A=10235:com.xingin.xhs U=0 visible=false visibleRequested=false mode=fullscreen}
    * ActivityRecord{dd1 u0 com.xingin.xhs/.index.v2.IndexActivityV2 t941}
""".trimIndent()

    private class Rig(private val dump: String) {
        val execs = CopyOnWriteArrayList<String>()
        val execOuts = CopyOnWriteArrayList<String>()
        val router = CarCommandRouter(
            exec = { execs += it },
            execOut = { cmd ->
                execOuts += cmd
                when {
                    cmd.startsWith("dumpsys activity activities") -> dump
                    cmd.startsWith("wm size") -> "Physical size: 1368x3192"
                    cmd.startsWith("am task resize") -> ""
                    else -> null
                }
            },
            displayId = { 43 },
            onStackEmpty = {},
            setDisplayPower = {},
            requestSyncFrame = {},
            repairLayerTree = {},
            sdkInt = { 29 }
        )
    }

    /** 捕获 stdout（[PipeLog.log] 直接写 System.out），用于断言跳过说明。 */
    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test
    fun `home root task on the vd is never moved back to the physical display`() {
        val r = Rig(dump)
        val log = captureStdout { r.router.moveAllTasksToDisplay(43, 0) }

        val moved = r.execs.filter { it.startsWith("am display move-stack") }
        assertTrue(
            "#940 是 home root task：搬回物理屏必然与系统重建的 #942 冲突，不得发命令；实际=$moved",
            moved.none { it == "am display move-stack 940 0" }
        )
        assertEquals(
            "普通任务必须照搬（顺序与段内一致）",
            listOf("am display move-stack 929 0", "am display move-stack 941 0"),
            moved
        )
        assertTrue(
            "跳过的 home task 必须落日志，现场可直接对账：$log",
            log.contains("skipping tasks=") && log.contains("home=[940]")
        )
        assertTrue(
            "home task 不得被 resize 到物理屏",
            r.execOuts.none { it.contains("am task resize 940 ") }
        )
    }

    @Test
    fun `task already moved by move top app is not moved a second time`() {
        // issue #220 现场：moveTopApp 发出 `move-stack 929 0` 后，异步入队尚未生效，
        // 紧接着的 dumpsys 仍是迁移前快照（段内仍有 929）。同一份 dump 喂给两个调用，
        // 精确复现该竞态：批量路径必须靠调用方告知的已搬 task 去重。
        val r = Rig(dump)
        val movedByTopApp = r.router.moveTopApp(43, 0, probe = false)
        assertEquals("moveTopApp 必须回报实际搬走的 task id", 929, movedByTopApp)

        r.router.moveAllTasksToDisplay(43, 0, skipTasks = setOfNotNull(movedByTopApp)) { }

        val moved = r.execs.filter { it.startsWith("am display move-stack") }
        assertEquals(
            "同一个 task 不得被搬第二次（否则必报 current taskDisplayArea）：$moved",
            listOf("am display move-stack 929 0", "am display move-stack 941 0"),
            moved
        )
    }

    /**
     * 实机 09:18（`main @ 963b7835`）新增形态：home 根任务 #945 下面挂着 launcher 子任务 #946
     * （`rootTaskId=945`、同样带 `type=home`）。只跳过 `homeTaskId` 认出的第一条会让 #946 被搬，
     * 报 `moveRootTaskToTaskDisplayArea: Unknown rootTaskId=946`。
     */
    private val dumpWithHomeChildTask = """
Display #0 (activities from top to bottom):
  * Task{phy2 #944 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
Display #45 (activities from top to bottom):
  * Task{5ad70f2 #945 type=home U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
    * ActivityRecord{ee1 u0 com.meizu.flyme.launcher/com.android.launcher3.uioverrides.QuickstepLauncher t945}
  * Task{d4410b2 #946 type=home I=com.meizu.flyme.launcher/com.android.launcher3.uioverrides.QuickstepLauncher U=0 rootTaskId=945 visible=true visibleRequested=true mode=fullscreen}
""".trimIndent()

    @Test
    fun `home child task under home root task is not moved`() {
        val r = Rig(dumpWithHomeChildTask)
        val log = captureStdout { r.router.moveAllTasksToDisplay(45, 0) }

        val moved = r.execs.filter { it.startsWith("am display move-stack") }
        assertTrue(
            "#946 是 #945 的子任务（rootTaskId=945），不是 root task，move-stack 必报 " +
                "Unknown rootTaskId：不得发命令；实际=$moved",
            moved.isEmpty()
        )
        assertTrue(
            "子任务也不得被 resize",
            r.execOuts.none { it.contains("am task resize 946 ") }
        )
        assertTrue(
            "整棵 home 树都要出现在跳过日志里：$log",
            log.contains("home=[945, 946]") || log.contains("home=[946, 945]")
        )
    }

    /**
     * 实机 09:29（`main @ 6870be52`）第三层：VD 段里混进客户端自己的 ActivityRecord 引用 `t948`
     * （该 task 实际在 Display #0），`move-stack 948 0` 报
     * `Trying to move rootTask=…#948 … to its current taskDisplayArea`。搬迁列表必须只认 task 头。
     */
    private val dumpWithForeignActivityRef = """
Display #0 (activities from top to bottom):
  * Task{phy3 #948 type=standard A=10343:com.dilinkauto.client U=0 visible=true visibleRequested=true mode=fullscreen}
Display #47 (activities from top to bottom):
  * Task{a44 #929 type=standard A=10265:com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen}
    Resumed: ActivityRecord{332e8fc u0 com.dilinkauto.client/.MainActivity t948}
""".trimIndent()

    @Test
    fun `task referenced only by activity record is not moved`() {
        val r = Rig(dumpWithForeignActivityRef)
        r.router.moveAllTasksToDisplay(47, 0)

        val moved = r.execs.filter { it.startsWith("am display move-stack") }
        assertEquals(
            "只出现在 Resumed ActivityRecord 里的 t948 不属于 VD，不得发 move-stack：$moved",
            listOf("am display move-stack 929 0"),
            moved
        )
    }

    /**
     * 同一 task 同时出现在两个 display 段（异步搬迁后的滞后快照）时，以首个出现的归属为准；
     * 已经在目标屏上的不得再搬。
     */
    private val dumpWithStaleDuplicate = """
Display #0 (activities from top to bottom):
  * Task{x99 #929 type=standard A=10265:com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen}
Display #47 (activities from top to bottom):
  * Task{x99 #929 type=standard A=10265:com.autonavi.minimap U=0 visible=true visibleRequested=true mode=fullscreen}
""".trimIndent()

    @Test
    fun `task already on target display is not moved again`() {
        val r = Rig(dumpWithStaleDuplicate)
        r.router.moveAllTasksToDisplay(47, 0)

        val moved = r.execs.filter { it.startsWith("am display move-stack") }
        assertTrue("已经在 display 0 上的 #929 不得再搬（否则报 current taskDisplayArea）：$moved", moved.isEmpty())
    }

    /**
     * issue #258 现场的第二条错误：`Unknown rootTaskId=941` 是旧快照里的残留，
     * 但正常路径下普通任务仍应被搬一次并 resize —— 证明跳过逻辑不会把普通任务一起吃掉。
     */
    @Test
    fun `ordinary residual task is still moved and resized`() {
        val r = Rig(dump)
        r.router.moveAllTasksToDisplay(43, 0)

        assertTrue(
            "普通残留 task 必须搬回并 resize",
            r.execs.contains("am display move-stack 941 0") &&
                r.execOuts.contains("am task resize 941 0 0 1368 3192")
        )
    }
}
