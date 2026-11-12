package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ActivityDumpParser` —— issue #77 的段落切分与 task 提取（两处复制实现的唯一来源）。
 *
 * 用 issue 给出的合成 dump 钉住两个缺陷的修复：
 *  - (a) 目标 display 是最后一个时不再吃到字符串末尾（跨屏泄漏）；
 *  - (b) `Task{…#N …}` 的引用行（`rootOfTask=` / `topDisplayFocusedRootTask=` / `PreferredTopFocusableRootTask=`）
 *        不再被当成 task 头，同一 id 只计一次。
 *
 * 期望值全部写字面量，不由被测函数反推。
 */
class ActivityDumpParserTest {

    /**
     * issue #77「确定性最小复现」的合成 dump（`aaa111/bbb222/ccc333` 为现场标记）。
     *
     * 修复前 `displaySection` + `Task\{[^}]*#(\d+)[ }]` 的结果是：
     *   display #0 → emitted=[1,1,1]              distinct=[1]
     *   display #9 → emitted=[178,178,178,345,345] distinct=[178,345]
     * 其中 `345`（物理屏 shizuku 任务）本不该出现在 #9 的切片里（跨屏泄漏）。
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

    /** 目标是最后一个 display（其后只有物理屏记述）时，切片不得吞进后续内容。 */
    @Test
    fun `last display section stops before the physical screen Display mDisplayId line`() {
        val section = ActivityDumpParser.displaySection(syntheticDump, 9)!!

        assertTrue("不得跨屏吞进物理屏记述", !section.contains("Display: mDisplayId="))
        assertTrue("不得跨屏吞进物理屏任务", !section.contains("#345"))
        assertTrue("段内内容本身要保留", section.contains("#178"))
    }

    /** display #0：issue 期望 `[1]`（修复前是 `[1,1,1]`）。 */
    @Test
    fun `display zero counts each task once`() {
        val section = ActivityDumpParser.displaySection(syntheticDump, 0)!!
        assertEquals(listOf(1), ActivityDumpParser.taskIdsInSection(section))
    }

    /** display #9：issue 期望 `[178]`（修复前是 `[178,178,178,345,345]`）。 */
    @Test
    fun `display nine counts each task once and excludes the leaked physical screen task`() {
        val section = ActivityDumpParser.displaySection(syntheticDump, 9)!!
        assertEquals(listOf(178), ActivityDumpParser.taskIdsInSection(section))
    }

    /** 段内 `emitted == distinct`：同一 id 只出现一次。 */
    @Test
    fun `emitted equals distinct for both displays`() {
        for (id in listOf(0, 9)) {
            val ids = ActivityDumpParser.taskIdsInSection(ActivityDumpParser.displaySection(syntheticDump, id)!!)
            assertEquals("display #$id 每个 task 只应出现一次", ids.size, ids.toSet().size)
        }
    }

    /** 只认 task 头：`rootOfTask=` / `topDisplayFocusedRootTask=` 这类引用行不得计入。 */
    @Test
    fun `reference lines are not counted as task headers`() {
        // 只有引用行、没有 `* Task{` 头的段 —— 头匹配必须为空。
        val onlyRefs = """
            Display #9 (activities from top to bottom):
              rootOfTask=true task=Task{bbb222 #178 type=home}
              topDisplayFocusedRootTask=Task{ccc333 #345 type=home}
        """.trimIndent()

        assertTrue(
            "引用行不得被当成 task 头",
            ActivityDumpParser.taskHeaders(onlyRefs).isEmpty()
        )
    }

    /** 头 + 引用指向同一 id 时按首次出现去重。 */
    @Test
    fun `header and reference with the same id collapse to one`() {
        val mixed = """
            Display #9 (activities from top to bottom):
              * Task{bbb222 #178 type=home visibleRequested=true mode=fullscreen}
                rootOfTask=true task=Task{bbb222 #178 type=home}
                * Hist  #0: ActivityRecord{22 u0 com.example/.Main t178}
        """.trimIndent()

        val headers = ActivityDumpParser.taskHeaders(mixed)
        assertEquals(1, headers.size)
        assertEquals("178", headers.single().groupValues[1])
        assertEquals(listOf(178), ActivityDumpParser.taskIdsInSection(mixed))
    }

    /** 段内有多个 task 头时按正文先后返回。 */
    @Test
    fun `multiple headers keep document order`() {
        val two = """
            Display #19 (activities from top to bottom):
              * Task{a #17 type=standard visibleRequested=true mode=fullscreen}
              * Task{b #347 type=standard visibleRequested=false mode=fullscreen}
        """.trimIndent()

        assertEquals(listOf(17, 347), ActivityDumpParser.taskIdsInSection(two))
    }

    /** 中间位置的目标 display：上界是下一个 `Display #` 段头。 */
    @Test
    fun `middle display stops at the next display header`() {
        val section = ActivityDumpParser.displaySection(syntheticDump, 0)!!
        assertTrue("不得吞进下一个 display 段", !section.contains("Display #9 "))
    }

    /** display 不存在时返回 null。 */
    @Test
    fun `absent display yields null section`() {
        assertNull(ActivityDumpParser.displaySection(syntheticDump, 77))
    }

    /** 段里一个 task 都没有时返回空列表。 */
    @Test
    fun `display without tasks yields empty id list`() {
        val empty = """
            Display #7 (activities from top to bottom):

            ActivityTaskSupervisor state:
              topDisplayFocusedRootTask=Task{aaa #1 type=home}
        """.trimIndent()

        assertEquals(emptyList<Int>(), ActivityDumpParser.taskIdsInSection(ActivityDumpParser.displaySection(empty, 7)!!))
    }

    /**
     * 行首的全局段标记构成上界；缩进的同名文字是**段内正文**，不是上界。
     *
     * 合成复现里 `  ActivityTaskSupervisor state: topDisplayFocusedRootTask=Task{…#178 …}` 是缩进的
     * （在 #9 段内部），若把它当上界就会把 `178` 切掉，反而漏认。这里用两个 display 造出对照：
     * #9 段内有一行缩进的 `ActivityTaskSupervisor state:`，其后紧跟 `* Task{…#178 …}`；
     * 段尾（行首 col0）才是真正的全局标记。
     */
    @Test
    fun `indented supervisor text stays inside the section while column zero marker bounds it`() {
        val dump = """
            Display #9 (activities from top to bottom):
              ActivityTaskSupervisor state: topDisplayFocusedRootTask=Task{bbb222 #178 type=home}
              * Task{bbb222 #178 type=home U=0 visible=true visibleRequested=true mode=fullscreen}
            ActivityTaskSupervisor state:
              topDisplayFocusedRootTask=Task{aaa #1 type=home}
        """.trimIndent()

        val section = ActivityDumpParser.displaySection(dump, 9)!!
        assertTrue("缩进的同名文字不得构成上界", section.contains("#178"))
        assertTrue("行首全局标记之后的内容不得进入段内", !section.contains("Task{aaa #1"))
        assertEquals(listOf(178), ActivityDumpParser.taskIdsInSection(section))
    }
}
