package com.dilinkauto.vdserver

/**
 * `dumpsys activity activities` 的 display 段切分与 task 提取（issue #77）。
 *
 * 修复前 `WindowStateMonitor.WindowStateParser` 与 `CarCommandRouter` 各有一份**逐字相同**的
 * `displaySection`，两份都：
 *  1. 只用「下一个 `Display #`」作结束标记 —— 目标 display 是最后一个（或其后不再出现段头）时
 *     `substring(start)` 一路吃到字符串末尾，把物理屏的 `Display: mDisplayId=0 …`、
 *     `ActivityTaskSupervisor state: …`、`topDisplayFocusedRootTask=Task{…#N …}` 等引用一起吞进来；
 *  2. 用 `Task\{[^}]*#(\d+)[ }]` 匹配 —— 同时命中 task 头与 `rootOfTask=true task=Task{…}` 这类引用行，
 *     同一 id 被计多次（`[Window]` 的 `n=` 虚高 2~3 倍），且引用行没有 `mode=`/`visibleRequested=`
 *     字段，是 `mode=<unknown>` 的来源。
 *
 * 现在只有这一份实现，两个调用方共用；逻辑是纯函数，单测直接调（见 `ActivityDumpParserTest`）。
 */
internal object ActivityDumpParser {

    /**
     * display 段的上界：以下任一标记中**最先出现**的那个即段尾。
     *
     * - `Display #<n> `（**必须行首**）：下一个 display 段头；
     * - `ActivityTaskSupervisor state:`（**必须行首**）：实机 dump 里 display 段之后的全局段；
     * - `Display: mDisplayId=`（允许缩进）：`Display #` 之外的另一种 display 记述 —— issue #77 的关键。
     *   实机里 VD 段是最后一个 display 时，段尾紧跟的正是被缩进 2 格的 `  Display: mDisplayId=0 (organized)`
     *   （**物理屏**记述），不加这条上界就会跨屏泄漏。它允许缩进，是因为合成复现里该行就是缩进的。
     *
     * `ActivityTaskSupervisor state:` 要求行首（实机在 col0），是因为合成复现里有缩进的
     * `  ActivityTaskSupervisor state: topDisplayFocusedRootTask=Task{…#178 …}` —— 那是**段内**一行普通文本，
     * 不是全局段头；若允许缩进就会把 `178` 一并切掉，反而漏认。
     */
    private val SECTION_END_RE = Regex(
        "(?m)^(?:" +
            "Display #\\d+ " +
            "|ActivityTaskSupervisor state:" +
            "|[ \\t]*Display: mDisplayId=" +
            ")"
    )

    /**
     * Task 头：行首缩进 + `* Task{…#<id> …}`。
     *
     * issue #77 的取舍：**只认带 `* ` 的项目符号、且处于行首的 Task 头**。实机与合成 dump 里
     * 段内的 task 一律打印成 `  * Task{…}`，而 `rootOfTask=… task=Task{…}`、
     * `topDisplayFocusedRootTask=Task{…}`、`PreferredTopFocusableRootTask=Task{…}`、
     * `(fullscreen) Task{…}`（TaskOrganizerController）这些引用行都**不以** `* Task{` 开头。
     * 宁可漏认（行首无 `*` 的非常规形态），也不误认 —— 误认会直接污染 #48/#59/#64 的取证结论。
     */
    val TASK_HEADER_RE = Regex("""(?m)^[ \t]*\*[ \t]*Task\{[^}]*#(\d+)[ }]""")

    /** ActivityRecord 行里的 task id：`ActivityRecord{… t<id>}`（与既有的 `findTaskSection` 同口径）。 */
    private val ACTIVITY_TASK_RE = Regex("""ActivityRecord\{[^}]*\bt(\d+)\}""")

    /**
     * 切出 `Display #<id> ` 开头的段，到 [SECTION_END_RE] 命中的上界为止。
     *
     * 找不到该 display 的段头返回 null。与旧实现不同：**必有上界**，目标 display 是最后一个
     * 时停在 `ActivityTaskSupervisor state:` / `Display: mDisplayId=` 等标记处，不再吞到字符串末尾。
     */
    fun displaySection(dump: String, id: Int): String? {
        val marker = "Display #$id "
        val start = dump.indexOf(marker)
        if (start < 0) return null
        val after = start + marker.length
        val end = SECTION_END_RE.find(dump, after)?.range?.first ?: dump.length
        return dump.substring(start, end)
    }

    /** 段内 task 头；同一 task id 只保留**首次**出现（issue #77 去重）。 */
    fun taskHeaders(section: String): List<MatchResult> {
        val seen = HashSet<Int>()
        return TASK_HEADER_RE.findAll(section).filter { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return@filter false
            seen.add(id)
        }.toList()
    }

    /**
     * 段内所有 task id：task 头 ∪ ActivityRecord 的 `t<id>` 引用，按正文出现先后去重。
     *
     * 供 `CarCommandRouter` 的 `residualTasks` 取证使用。既接受 task 头（正常形态），也接受
     * `ResumedActivity: ActivityRecord{… t178}` 这类 ActivityRecord 行 —— 这是 issue #77 期望
     * `display #9` 输出 `[178]` 的依据（该段只有 ResumedActivity 行带 `t178`，没有 `* Task{` 头）；
     * 与 `CarCommandRouter.findTaskSection` 判断「某段是否含某 task」时的口径一致。
     * 引用行（`topDisplayFocusedRootTask=Task{…}`）不带 `t<id>}`，因此不会被计入。
     */
    fun taskIdsInSection(section: String): List<Int> {
        val hits = ArrayList<Pair<Int, Int>>()
        for (m in TASK_HEADER_RE.findAll(section)) {
            m.groupValues[1].toIntOrNull()?.let { hits += m.range.first to it }
        }
        for (m in ACTIVITY_TASK_RE.findAll(section)) {
            m.groupValues[1].toIntOrNull()?.let { hits += m.range.first to it }
        }
        hits.sortBy { it.first }
        val out = ArrayList<Int>(hits.size)
        for ((_, id) in hits) if (id !in out) out += id
        return out
    }
}
