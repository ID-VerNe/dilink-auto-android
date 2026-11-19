package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue #17：把 Display #0 的 home 任务迁进 VD 之前，先从 `dumpsys activity activities`
 * 里认出它（只从 Display #0 段取，绝不跨屏）。
 *
 * 四种形态：`type=home` 直认、`A=<launcher包名>/…` 兜底、两者都没有、home 只在别的 display。
 * dump 按实机格式重建（段内 task 一律 `  * Task{…}`，带 `rootOfTask=` 引用行），不需要设备。
 */
class ActivityDumpParserHomeTaskTest {

    /** Display #0 段里直接带 `type=home` 的任务 #123。 */
    private val dumpWithHomeTask = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{aaa111 #123 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
            rootOfTask=true task=Task{aaa111 #123 type=home}
            topDisplayFocusedRootTask=Task{aaa111 #123 type=home}

          Resumed activities in task display areas (from top to bottom):
            Resumed: ActivityRecord{11 u0 com.android.launcher3/.uioverrides.QuickstepLauncher t123}

        Display #27 (activities from top to bottom):

        ActivityTaskSupervisor state:
    """.trimIndent()

    /** Display #0 段的 launcher 任务没有 `type=home`，只有 `A=` 亲和性字段。 */
    private val dumpWithAffinityOnly = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{bbb222 #321 type=standard A=com.android.launcher3/.uioverrides.QuickstepLauncher U=0 visible=true visibleRequested=true mode=fullscreen}
            rootOfTask=true task=Task{bbb222 #321 type=standard A=com.android.launcher3/.uioverrides.QuickstepLauncher}
            topDisplayFocusedRootTask=Task{bbb222 #321 type=standard A=com.android.launcher3/.uioverrides.QuickstepLauncher}

        Display #27 (activities from top to bottom):

        ActivityTaskSupervisor state:
    """.trimIndent()

    /** Display #0 段里既没有 `type=home`，也没有 launcher 包名。 */
    private val dumpWithoutHome = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{ccc333 #7 type=standard A=10001:com.example U=0 visible=true visibleRequested=true mode=fullscreen}
            rootOfTask=true task=Task{ccc333 #7 type=standard A=10001:com.example}
            topDisplayFocusedRootTask=Task{ccc333 #7 type=standard A=10001:com.example}

        Display #27 (activities from top to bottom):

        ActivityTaskSupervisor state:
    """.trimIndent()

    /** home 任务只存在于 Display #27，Display #0 段是空的。 */
    private val dumpWithHomeOnOtherDisplay = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):

        Display #27 (activities from top to bottom):
          * Task{ddd444 #555 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
            rootOfTask=true task=Task{ddd444 #555 type=home}
            topDisplayFocusedRootTask=Task{ddd444 #555 type=home}

        ActivityTaskSupervisor state:
    """.trimIndent()

    private fun zeroSection(dump: String): String =
        ActivityDumpParser.displaySection(dump, 0)!!

    @Test
    fun `type home task on display zero is found`() {
        assertEquals(123, ActivityDumpParser.homeTaskId(zeroSection(dumpWithHomeTask)))
    }

    @Test
    fun `task is found by launcher package when type home is absent`() {
        val section = zeroSection(dumpWithAffinityOnly)
        assertNull("没有 type=home 时 homeTaskId 应为 null", ActivityDumpParser.homeTaskId(section))
        assertEquals(321, ActivityDumpParser.taskIdByPackage(section, "com.android.launcher3"))
    }

    @Test
    fun `no home task yields null`() {
        val section = zeroSection(dumpWithoutHome)
        assertNull(ActivityDumpParser.homeTaskId(section))
        assertNull(ActivityDumpParser.taskIdByPackage(section, "com.android.launcher3"))
    }

    @Test
    fun `home task on another display is not picked up from display zero`() {
        val section = zeroSection(dumpWithHomeOnOtherDisplay)
        assertEquals("Display #0 段必须为空", "", section.substringAfter("Display #0 (activities from top to bottom):").trim())
        assertNull(ActivityDumpParser.homeTaskId(section))
    }
}
