package com.dilinkauto.vdserver

import com.dilinkauto.protocol.AppTargets
import com.dilinkauto.protocol.AppUninstalledMessage
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.LaunchAppMessage
import com.dilinkauto.protocol.RecentsParser
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.requireComponentName
import com.dilinkauto.protocol.requirePackageName
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything the car can ask for over `Channel.CONTROL`, plus the display
 * queries the lifecycle needs (audit R3-SRP-10).
 *
 * Extracted from [PipelineServer] so that class keeps only process-lifecycle
 * ownership and collaborator wiring. All shell access goes through the two
 * injected functions, so this class has no thread or socket state of its own.
 *
 * @param exec write a line to the persistent shell's stdin.
 * @param execOut run `sh -c` and capture stdout, or null on failure.
 * @param displayId the virtual display id; -1 before VD creation.
 * @param onStackEmpty signal the phone that the VD activity stack drained.
 * @param setDisplayPower toggle the physical panel (`SET_DISPLAY_POWER`).
 * @param requestSyncFrame ask the pipeline to emit an IDR on the video channel
 *   (`REQUEST_SYNC_FRAME`，issue #9). Pipeline-thread owned, hence a plain flag
 *   handoff rather than an encoder call from this (Lifecycle/Touch) thread.
 * @param repairLayerTree SF 层树脱同步的最后一级修复（`VirtualDisplayCreator.ensureDtaAttached()`），
 *   由 [healDisplay] 在 WM 侧判据都正常时调用（issue #15），以及 [recheckLayersAfterFill]
 *   在 `fill` 之后补一次复查（issue #19）。健康设备上它零副作用。
 * @param allowedPackages 车机可见应用的包名集合（issue #21，来自部署端 argv）。空集合 =
 *   部署端没下传，建屏恢复不做白名单过滤（车机/桌面 ADB 路径的既有行为）。
 */
internal class CarCommandRouter(
    private val exec: (String) -> Unit,
    private val execOut: (String) -> String?,
    private val displayId: () -> Int,
    private val onStackEmpty: () -> Unit,
    private val setDisplayPower: (Boolean) -> Unit,
    private val requestSyncFrame: () -> Unit,
    private val repairLayerTree: () -> Unit,
    private val allowedPackages: Set<String> = emptySet(),
) {
    /** [healDisplay] 的并发去重：车机端每 15s 重试一次，叠加时不能嵌套执行。 */
    private val healInFlight = AtomicBoolean(false)

    /**
     * [recheckLayersAfterFill] 的并发去重（issue #19）。
     *
     * 连点启动多个应用会并发跑出多个 fill 线程；旋转往返本身会抖动 display，
     * 同一时刻只允许一次。被跳过的那次由 #15 的 [healDisplay] 阶梯兜底
     * （车机侧 6s 判黑 → 15s 自愈）。
     */
    private val layerRecheckInFlight = AtomicBoolean(false)

    fun dispatch(f: FrameCodec.Frame) {
        PipeLog.log("Car command received: 0x${Integer.toHexString(f.messageType.toInt() and 0xFF)}")
        when (f.messageType) {
            ControlMsg.LAUNCH_APP -> launchApp(LaunchAppMessage.decode(f.payload).packageName)
            ControlMsg.GO_BACK -> { exec("input -d ${displayId()} keyevent 4"); checkStackEmpty() }
            ControlMsg.GO_HOME -> { exec("input -d ${displayId()} keyevent 3"); checkStackEmpty() }
            ControlMsg.GO_RECENT -> quickSwitch()
            ControlMsg.APP_UNINSTALL -> {
                // S-02: the payload is interpolated into a shell line running as
                // shell UID (audit S-02). LaunchAppMessage/AppUninstalledMessage
                // validate the shape at the decoder; quote it too, so the shell
                // word boundary is explicit even for a conforming name.
                val pkg = AppUninstalledMessage.decode(f.payload).packageName
                exec("pm uninstall ${VdDeploy.shellQuote(pkg)}")
            }
            ControlMsg.APP_INFO -> {
                val pkg = String(f.payload, Charsets.UTF_8)
                val safePkg = try { requirePackageName(pkg) } catch (e: Exception) { PipeLog.err("APP_INFO: ${e.message}"); return }
                val s = execOut("cmd package resolve-activity --brief -a android.settings.APPLICATION_DETAILS_SETTINGS com.android.settings")?.trim()
                if (!s.isNullOrEmpty()) exec("am start --display ${displayId()} -n $s -d ${VdDeploy.shellQuote("package:$safePkg")}") else exec("am start --display ${displayId()} -a android.settings.APPLICATION_DETAILS_SETTINGS -d ${VdDeploy.shellQuote("package:$safePkg")}")
            }
            // 手动开关手机物理屏（payload: 1 字节，0=熄屏 1=亮屏）。
            // 默认连上即自动熄屏（bindAndAccept），这条只用于中途手动干预；
            // 空 payload 按"熄屏"处理，避免对端漏填载荷时误把屏幕点亮。
            ControlMsg.SET_DISPLAY_POWER -> {
                val on = f.payload.isNotEmpty() && f.payload[0].toInt() != 0
                setDisplayPower(on)
                PipeLog.log("Physical display power -> ${if (on) "ON" else "OFF"}")
            }
            // 车机端参考链断了要一个 IDR（issue #9），payload 忽略。
            // 这里只置位；真正 setParameters 发生在 Pipeline 线程（见 GlPipeline.pipelineLoop）。
            ControlMsg.REQUEST_SYNC_FRAME -> {
                requestSyncFrame()
                PipeLog.log("Car requested sync frame (IDR)")
            }
            // 车机端"解码存活但内容持续纯黑"时请求显示自愈（issue #15），payload 忽略。
            ControlMsg.REQUEST_DISPLAY_HEAL -> healDisplay()
        }
    }

    private fun launchApp(pkg: String) {
        try {
            // S-02: pkg is peer-controlled (the car) and reaches `sh -c` as shell
            // UID. Validate the shape here as well as at the decoder, and
            // shell-quote every interpolation. The resolve-activity *output* is
            // validated as a component before it is used in `am start -n`.
            val safePkg = requirePackageName(pkg)
            val quotedPkg = VdDeploy.shellQuote(safePkg)
            PipeLog.log("launchApp: pkg=$safePkg display=${displayId()}")
            val raw = execOut("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $quotedPkg 2>/dev/null | tail -1")?.trim()
            // resolve-activity 失败时会返回 "No activity found" 之类的文本，不是组件名；
            // 只有形如 "pkg/activity" 且不含空格的输出才可信，否则回退到隐式 intent。
            val component = raw?.takeIf { it.contains('/') && !it.contains(' ') }
            val out = if (component != null) {
                val safeComponent = try { requireComponentName(component) } catch (e: Exception) {
                    PipeLog.err("launchApp: rejecting non-component resolve output")
                    null
                }
                if (safeComponent != null) {
                    PipeLog.log("launchApp: resolved component=$safeComponent")
                    val o = execOut("am start --display ${displayId()} -n ${VdDeploy.shellQuote(safeComponent)}")
                    fillLaunchedApp(safePkg)
                    o
                } else null
            } else {
                PipeLog.log("launchApp: resolve raw='${raw ?: "<null>"}' not a component — fallback to implicit intent")
                val o = execOut("am start --display ${displayId()} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $quotedPkg")
                fillLaunchedApp(safePkg)
                o
            }
            // 同步执行并记录 am start 的完整输出（成功是 "Starting: Intent ..."，失败会带 Error/Exception）
            PipeLog.log("launchApp: am start result='${out?.trim()?.replace(Regex("\\s+"), " ") ?: "<no output>"}'")
        } catch (e: Exception) { PipeLog.err("launch: ${e.message}") }
    }

    /** Ping the phone when the last VD activity finishes, so it can stop mirroring. */
    private fun checkStackEmpty() {
        Thread({
            try {
                Thread.sleep(300) // 等 keyevent 生效后再查询
                val id = displayId()
                val sec = displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                val empty = sec == null || sec.lines().none { it.contains("Task{") }
                PipeLog.log("checkStackEmpty: display=$id empty=$empty")
                if (empty) onStackEmpty()
            } catch (e: Exception) { PipeLog.err("checkStackEmpty: ${e.message}") }
        }, "StackCheck").start()
    }

    /**
     * VD 内容持续纯黑时的显示自愈阶梯（issue #15）。
     *
     * 实车证据（issue #15）：一个会话里连续启动第 4 个应用后，该 display 的顶层
     * task 停在 `visible=true visibleRequested=false ... translucent=true`、且没有
     * `topResumedActivity=`，编码器随之持续输出极小 I 帧（车机侧 32 秒纯黑）；同时
     * `input -d <vd> keyevent 3/4`（0x11/0x12）在该 ROM 上对该 display 完全无效，
     * 手机侧没有任何恢复手段，用户只能断连重连。
     *
     * 阶梯（每步复查，成功即返回，避免多余的 display 抖动）：
     *  1. `am start --display <id> --task <taskId> -n <cmp>`：把"已存在但没 resume"的
     *     顶层任务重新拉到本 display 前台（[quickSwitch] 的同一形态命令已在实机验证
     *     可把任务迁移到指定 display）；
     *  2. `am display move-stack <taskId> 0` → `... <id>` 往返：让 WM 重新把任务关联
     *     到该 display（往返期间任务短暂落到手机物理屏，而会话期间物理屏已被
     *     `cmd display power-off 0` 熄灭，用户不可见）；
     *  3. [repairLayerTree]：WM 判据都正常但画面仍黑时才怀疑 SF 层树（Flyme DTA
     *     孤立），复跑创建期那套旋转往返修复。
     *
     * 全程后台线程（3 次 dumpsys + 2 次 move-stack 合计数百毫秒，绝不阻塞
     * TouchReader）；[healInFlight] 去重。display 段里连 Task 都没有时**不做任何事**
     * —— 那是 [checkStackEmpty] 的"空栈自动断开"语义，不能在这里把用户踢回桌面。
     */
    private fun healDisplay() {
        if (!healInFlight.compareAndSet(false, true)) {
            PipeLog.log("healDisplay: already in flight, skip")
            return
        }
        Thread({
            try {
                val id = displayId()
                if (id < 0) { PipeLog.log("healDisplay: skipped, display id unknown"); return@Thread }
                val sec = displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                PipeLog.log("healDisplay: display=$id before='${summarizeDisplay(sec)}'")
                if (sec == null || !sec.contains("Task{")) {
                    PipeLog.log("healDisplay: display=$id has no task — leave the empty-stack semantics alone")
                    return@Thread
                }
                if (isTopTaskVisible(sec)) {
                    // WM 侧看起来是可见的，但车机持续报内容黑 —— 现场日志（issue #15）
                    // 出现过"会话结束 dump 已恢复 visibleRequested=true、画面仍是黑"，
                    // 所以这里不能直接返回，要走最后的层树检查（健康的 DTA 检查零副作用）。
                    PipeLog.log("healDisplay: display=$id top task visible in WM but content is black — verify layer tree")
                } else {
                    val m = TOP_ACTIVITY_RE.find(sec)
                    val cmp = m?.groupValues?.get(1)
                    val taskId = m?.groupValues?.get(2)?.toIntOrNull()
                    if (cmp.isNullOrBlank() || taskId == null || taskId <= 0) {
                        PipeLog.log("healDisplay: no top ActivityRecord in display=$id, falling through to layer-tree repair")
                    } else {
                        val out = execOut("am start --display $id --task $taskId -n ${VdDeploy.shellQuote(cmp)}")
                        PipeLog.log("healDisplay: step1 am start -> ${out?.trim()?.replace(Regex("\\s+"), " ")?.take(200) ?: "<no output>"}")
                        Thread.sleep(HEAL_VERIFY_MS)
                        val after1 = displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                        PipeLog.log("healDisplay: after step1='${summarizeDisplay(after1)}'")
                        if (after1 != null && isTopTaskVisible(after1)) {
                            PipeLog.log("healDisplay: recovered by step1 (re-front task $taskId)")
                            return@Thread
                        }
                        execOut("am display move-stack $taskId 0")
                        Thread.sleep(HEAL_VERIFY_MS)
                        execOut("am display move-stack $taskId $id")
                        Thread.sleep(HEAL_VERIFY_MS)
                        val after2 = displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                        PipeLog.log("healDisplay: after step2='${summarizeDisplay(after2)}'")
                        if (after2 != null && isTopTaskVisible(after2)) {
                            PipeLog.log("healDisplay: recovered by step2 (move-stack round trip)")
                            return@Thread
                        }
                    }
                }
                PipeLog.log("healDisplay: step3 layer-tree repair")
                repairLayerTree()
                val after3 = displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                PipeLog.log("healDisplay: after step3='${summarizeDisplay(after3)}'")
                if (after3 != null && isTopTaskVisible(after3)) {
                    PipeLog.log("healDisplay: recovered by step3 (layer-tree repair)")
                } else {
                    PipeLog.err("healDisplay: still black after the whole ladder — see the dumps above")
                }
            } catch (e: Exception) {
                PipeLog.err("healDisplay: ${e.message}")
            } finally {
                healInFlight.set(false)
            }
        }, "DisplayHeal").start()
    }

    /**
     * 会话建立后确认 VD 里真的有窗口（issue #17）。
     *
     * 现场证据（`dilinkauto-logs(10).zip`）：新建 VD 后 `am start --display N -a MAIN
     * -c HOME` 对已存在的 launcher 任务只做 bring-to-front（`Warning: Activity not
     * started, its current task has been brought to the front`），不会在 N 上建任务；
     * [moveTopApp] 又显式跳过 launcher / systemui / 自家控制端 —— 于是 display 段里
     * 连一个 `Task{` 都没有，编码器只能输出纯黑关键帧（`Pipeline: … tex=0 cb=0`），
     * 车机侧判定黑屏后反复重握手，用户看到的一直是黑屏；[healDisplay] 对"空段"按
     * 空栈语义直接返回，救不了它。
     *
     * 补救：把最近一次被镜像的应用迁回 VD —— `am start --display N --task <id> -n <cmp>`
     * 的跨 display 迁移能力已在实机验证（见 [quickSwitch]），目标选择沿用
     * [RecentsParser.pickQuickSwitchTarget]，因此不会把自家控制端搬进来（会踩
     * "no focused window" → ANR）。
     *
     * 不搬 launcher/home 任务：`type=home` 任务跨 display 迁移没有实机证据，VD 释放时
     * home task 的回收语义也不明确（有连 launcher 一起销毁的风险）。
     *
     * 同步执行（创建期命令本来就在同一线程串行）：2 次 dumpsys + 1 次 am start +
     * 400ms 复查，远小于 30s 的 car accept 超时；失败只记日志，绝不阻塞会话。
     */
    fun ensureDisplayHasContent(id: Int) {
        if (id < 0) return
        try {
            val sec = displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
            if (sec != null && sec.contains("Task{") && isTopTaskVisible(sec)) {
                PipeLog.log("ensureContent: display=$id already has a visible task")
                return
            }
            val current = sec?.let { TOP_ACTIVITY_RE.find(it) }?.groupValues?.get(2)?.toIntOrNull()
            val recents = execOut("dumpsys activity recents 2>/dev/null")
                ?.let { RecentsParser.parse(it) } ?: emptyList()
            // issue #21：建屏恢复属于系统替用户做的动作，只在车机白名单内挑选目标；
            // 白名单外的应用（银行/相册/Shizuku 管理器等）不得被拉到车机屏。
            val target = RecentsParser.pickQuickSwitchTarget(recents, current, SELF_PACKAGE, allowedPackages)
            PipeLog.log(
                "ensureContent: display=$id has no visible window (before='${summarizeDisplay(sec)}'), " +
                    "${recents.size} live tasks, allowlist=" +
                    (allowedPackages.size.takeIf { it > 0 }?.toString() ?: "<not handed down>") +
                    ", target=" +
                    (target?.let { "${it.packageName}#${it.taskId}" } ?: "<none>")
            )
            if (target == null) {
                PipeLog.log("ensureContent: display=$id nothing to restore — first frames stay black until an app is launched")
                return
            }
            val out = execOut("am start --display $id --task ${target.taskId} -n ${VdDeploy.shellQuote(target.component)}")
            PipeLog.log("ensureContent: am start -> ${out?.trim()?.replace(Regex("\\s+"), " ")?.take(200) ?: "<no output>"}")
            Thread.sleep(HEAL_VERIFY_MS)
            val after = displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
            if (after != null && isTopTaskVisible(after)) {
                PipeLog.log("ensureContent: restored ${target.packageName} (Task ${target.taskId}) on display=$id")
                fillTask(target.taskId, id)
            } else {
                PipeLog.err("ensureContent: ${target.packageName} did not become display $id's visible task (after='${summarizeDisplay(after)}')")
            }
        } catch (e: Exception) {
            PipeLog.err("ensureContent: ${e.message}")
        }
    }

    /**
     * 「最近」= 快速切换到上一个任务（2026-10-10 用户拍板的语义）。
     *
     * 不用 `input -d N keyevent 187`：AOSP 的系统 recents 硬编码作用于默认显示器
     * —— `-d N` 注入的 APP_SWITCH 实测只会 toggle **物理屏**的最近任务（搅乱用户
     * 正在用的物理屏），对 VD 完全无效；系统也没有 per-display recents 的 shell
     * 接口（`wm shell` 无相关命令、Launcher 的 RecentsActivity 未导出）。
     *
     * 替代实现：`dumpsys activity recents` 选「最近活跃、非当前 VD 顶层、非自家
     * 控制端」的任务 → `am start --display N --task <id> -n <cmp>` 拉到 VD 前台
     * —— 实测该命令可把任务从任意 display 迁移过来，语义与「双击 recents 快速
     * 切换」一致。整段异步（两个 dumpsys 各几百毫秒），绝不阻塞 TouchReader。
     */
    private fun quickSwitch() {
        Thread({
            try {
                val id = displayId()
                val recents = execOut("dumpsys activity recents 2>/dev/null")
                    ?.let { RecentsParser.parse(it) } ?: emptyList()
                val activities = execOut("dumpsys activity activities 2>/dev/null")
                val current = activities?.let { displaySection(it, id) }
                    ?.let { TOP_ACTIVITY_RE.find(it) }?.groupValues?.get(2)?.toIntOrNull()
                val target = RecentsParser.pickQuickSwitchTarget(recents, current, SELF_PACKAGE)
                PipeLog.log(
                    "quickSwitch: ${recents.size} live tasks, current=$current, target=" +
                        (target?.let { "${it.packageName}#${it.taskId}" } ?: "<none>")
                )
                if (target != null) {
                    val out = execOut("am start --display $id --task ${target.taskId} -n ${VdDeploy.shellQuote(target.component)}")
                    PipeLog.log("quickSwitch: am start -> ${out?.trim()?.replace(Regex("\\s+"), " ")?.take(200) ?: "<no output>"}")
                    // 旧会话遗留的小窗任务切回前台时同样铺满（新任务由 launchApp 铺满）。
                    fillTask(target.taskId, id)
                    // 跨 display 迁移 + resize 同样是脱同步的触发形态（issue #19）。
                    recheckLayersAfterFill()
                }
            } catch (e: Exception) { PipeLog.err("quickSwitch: ${e.message}") }
        }, "QuickSwitch").start()
    }

    /**
     * VD 上的 app 默认落成 freeform 小窗（2026-10-10 实机：启动参数
     * `--windowingMode 1` 被框架忽略，任务仍 `mode=freeform`、bounds 只有
     * 853,51-1487,1220），铺满 VD 的可靠做法是启动后
     * `am task resize <taskId> 0 0 <W> <H>` —— 实测窗口铺满 0,0-2340,1316。
     *
     * 目标 task 取自 `dumpsys activity activities` display 段的**首个
     * ActivityRecord**（= 该段顶层任务的顶层 activity），并要求其组件以刚启动
     * 的包名开头 —— 启动是异步的，轮询至多 [FILL_RETRIES] 次；顶层还是旧 app
     * 时不会被误改（这正是「按包名在整个段里搜」会踩的坑：同包旧任务会先被搜到）。
     *
     * 全程后台线程（dumpsys + resize 合计数百毫秒，绝不阻塞 TouchReader）。
     * 失败只记日志 —— 窗口小不是错误，不值得惊动车机。
     */
    private fun fillLaunchedApp(pkg: String) {
        Thread({
            try {
                val id = displayId()
                if (id >= 0) {
                    var taskId: Int? = null
                    for (attempt in 1..FILL_RETRIES) {
                        val sec = displaySection(execOut("dumpsys activity activities 2>/dev/null") ?: "", id)
                        val m = sec?.let { TOP_ACTIVITY_RE.find(it) }
                        val comp = m?.groupValues?.get(1)
                        if (comp != null && comp.startsWith("$pkg/")) {
                            taskId = m.groupValues[2].toIntOrNull()
                            break
                        }
                        Thread.sleep(FILL_POLL_MS)
                    }
                    if (taskId == null) {
                        PipeLog.log("launchApp: fill skipped — '$pkg' never became display $id's top task")
                    } else {
                        fillTask(taskId, id)
                        // issue #19：DTA 被踢出层树就发生在这次 resize 之后约 1.6s
                        // （创建期那次检查必然读到健康），所以 fill 完必须再查一次。
                        recheckLayersAfterFill()
                    }
                }
            } catch (e: Exception) { PipeLog.err("launchApp fill: ${e.message}") }
        }, "FillDisplay").start()
    }

    /**
     * resize 一个 task 铺满 display：`am task resize <taskId> 0 0 <W> <H>`，
     * 尺寸取 `wm size -d <id>` 的首个 `WxH`（实机输出 "Physical size: 2340x1316"）。
     * 只对 freeform 任务生效；非 resizeable / 非 freeform 任务上 am 会打印
     * 错误并返回 —— best-effort，仅记日志。也用于 [quickSwitch]。
     */
    private fun fillTask(taskId: Int, id: Int) {
        val size = execOut("wm size -d $id 2>/dev/null")
        val dims = WM_SIZE_RE.find(size ?: "")
        val w = dims?.groupValues?.get(1)?.toIntOrNull()
        val h = dims?.groupValues?.get(2)?.toIntOrNull()
        if (taskId <= 0 || w == null || h == null) {
            PipeLog.log("fill: skipping task $taskId (display $id size='${size?.trim() ?: "<null>"}')")
            return
        }
        val out = execOut("am task resize $taskId 0 0 $w $h")
        PipeLog.log("fill: task $taskId -> 0,0-$w,$h out='${out?.trim()?.replace(Regex("\\s+"), " ")?.take(120) ?: "<no output>"}'")
    }

    /**
     * `fill` 之后延迟复查一次 SF 层树（issue #19）。
     *
     * 为什么不能紧跟着查：实车证据是 `am task resize` 之后**约 1.6s** 才看到
     * `DefaultTaskDisplayArea` 被挪到根的 `Offscreen Hierarchy` 下。立刻查会读到
     * 脱同步之前的状态（健康）→ 判定"无需修复"，正好漏掉这次故障。
     *
     * 为什么挂在 fill 之后而不是帧循环轮询（候选 B）：健康会话不必持续付出
     * dumpsys 开销；fill 是已确认的唯一触发点（创建期检查由
     * `PipelineServer` 建屏后那次覆盖）。
     *
     * 全程在当前后台线程（"FillDisplay"/"QuickSwitch"）里跑，绝不阻塞 TouchReader。
     * 健康设备上 [repairLayerTree] 只做一次 dumpsys 就返回；只有在真的孤立时才
     * 会做旋转往返（最多 2 轮 × 3s）。
     */
    private fun recheckLayersAfterFill() {
        if (!layerRecheckInFlight.compareAndSet(false, true)) {
            PipeLog.log("layer recheck: already in flight, skip")
            return
        }
        try {
            Thread.sleep(LAYER_RECHECK_DELAY_MS)
            repairLayerTree()
        } catch (e: Exception) {
            PipeLog.err("layer recheck: ${e.message}")
        } finally {
            layerRecheckInFlight.set(false)
        }
    }

    fun moveTopApp(fromDisplay: Int, toDisplay: Int) {
        if (fromDisplay < 0 || toDisplay < 0) return
        // `am display move-stack` was added in API 29 (Android 10). On API 26-28 the
        // subcommand is absent — am prints a usage error and exits non-zero. Skip the
        // whole feature on older levels so the foreground app is left in place rather
        // than a silent shell failure masking as success.
        if (android.os.Build.VERSION.SDK_INT < 29) {
            PipeLog.log("moveTopApp: skipping, am display move-stack requires API 29 (current ${android.os.Build.VERSION.SDK_INT})")
            return
        }
        try {
            val dump = execOut("dumpsys activity activities 2>/dev/null") ?: return
            val sec = displaySection(dump, fromDisplay)
            if (sec == null) {
                PipeLog.log("moveTopApp: no 'Display #$fromDisplay' section found, skip")
                return
            }
            val match = TOP_ACTIVITY_RE.find(sec)
            val topComponent = match?.groupValues?.get(1)
            val taskId = match?.groupValues?.get(2)
            // 跳过自家控制端：DiLinkAuto 自己的 Activity 搬进 VD 没有镜像价值
            // （用户在车机上看到"镜像的是镜像工具本身"），且它一旦成为 VD 上
            // resumed 的 Activity 就踩 "no focused window" 输入派发超时 → ANR
            // 弹窗；真机 2026-10-09：用户点 ANR 弹窗的"关闭应用"，顺带杀死整个
            // 服务进程，全部连接断开（用户视角 = "点主页立马闪退"）。
            // launcher / systemui 原本就跳过（搬了会遮挡用户 app）。
            val isSelf = topComponent != null && topComponent.startsWith(SELF_PACKAGE + "/")
            val skip = topComponent == null || taskId == null ||
                topComponent.contains("launcher", true) ||
                topComponent.contains("systemui", true) || isSelf
            if (!skip) {
                PipeLog.log("Moving app $topComponent (Task $taskId) from display $fromDisplay to $toDisplay")
                exec("am display move-stack $taskId $toDisplay")
            } else {
                PipeLog.log("moveTopApp: nothing to move (top=${topComponent ?: "<none>"}, task=${taskId ?: "-"}, self=$isSelf)")
            }
        } catch (e: Exception) {
            PipeLog.err("Failed to move app: ${e.message}")
        }
    }

    /**
     * Slice a `dumpsys activity activities` dump down to one display's section —
     * the text from `Display #<id> ` up to the next `Display #` header.
     *
     * Both [checkStackEmpty] and [moveTopApp] need exactly this window and used
     * to inline it twice. Returns null when the display has no section at all.
     */
    private fun displaySection(dump: String, id: Int): String? {
        val marker = "Display #$id "
        val start = dump.indexOf(marker)
        if (start < 0) return null
        val next = dump.indexOf("Display #", start + marker.length)
        return if (next >= 0) dump.substring(start, next) else dump.substring(start)
    }

    /**
     * 该 display 段的顶层 Task 是否 `visibleRequested=true`。
     *
     * 只取段内第一个 Task{}（activity 按 top→bottom 排列，首个即顶层）。判据来自
     * issue #15 的现场差异：健康时 `visible=true visibleRequested=true` 且带
     * `topResumedActivity=`；黑屏时同一行是 `visible=true visibleRequested=false`。
     * `visibleRequested=false` 表示窗口容器没有请求可见 —— 无论 WM 是否还标着
     * `visible=true`，该 display 上都不会有内容被合成。
     */
    private fun isTopTaskVisible(section: String): Boolean {
        val inner = TOP_TASK_RE.find(section)?.groupValues?.get(1) ?: return false
        return inner.contains("visibleRequested=true")
    }

    /**
     * 把 display 段压成单行并保留足够长度，供 [healDisplay] 落盘。
     *
     * 全局 [ShellExec] 只记 400 字符摘要，`visibleRequested` 之后的 ActivityRecord
     * 列表、`isSleeping`、bounds 全被截掉 —— issue #15 就是因为这个截断无法在
     * "WM 未 resume"与"SF 层树脱同步"之间实锤。只在这一条路径上放开到 2000 字符。
     */
    private fun summarizeDisplay(section: String?): String {
        if (section == null) return "<no section>"
        return section.trim().replace(Regex("\\s+"), " ").take(HEAL_DUMP_LOG_CHARS)
    }

    private companion object {
        /**
         * 手机控制端包名，从跨模块常量派生（见 [AppTargets] 的说明：applicationId
         * 变更时集中在这里改，不在各模块散落硬编码）。[moveTopApp] 用它跳过
         * 把控制端自身搬进 VD —— 理由见那段的注释。[quickSwitch] 用它跳过
         * 把「切换目标」选成控制端自身。
         */
        val SELF_PACKAGE = AppTargets.PHONE_MAIN_ACTIVITY.substringBefore('/')

        /**
         * `dumpsys activity activities` 里的 ActivityRecord 行：捕获 组件 与 所在
         * task id。在某个 display 段内首个匹配即该段顶层（ActivityRecord 按
         * top→bottom 排列）：[moveTopApp] 用组件判断要不要搬，[quickSwitch]
         * 用 task id 排除「当前顶层」。
         */
        val TOP_ACTIVITY_RE = Regex("ActivityRecord\\{[^ ]+ [^ ]+ ([^/ ]+/[^ } ]+) t(\\d+)\\}")

        /**
         * 「铺满 VD」的轮询预算：8 × 300ms ≈ 2.4s，覆盖 am start 的异步落地
         * （见 [fillLaunchedApp]）。
         */
        const val FILL_RETRIES = 8
        const val FILL_POLL_MS = 300L

        /** `wm size -d <id>` 输出 "Physical size: 2340x1316" 里的首个 WxH。 */
        val WM_SIZE_RE = Regex("(\\d+)x(\\d+)")

        /**
         * `dumpsys activity activities` 的 Task 行：取花括号内文本，供
         * [isTopTaskVisible] 读 `visibleRequested`（issue #15）。
         */
        val TOP_TASK_RE = Regex("Task\\{([^}]*)\\}")

        /** [healDisplay] 每步复查前的等待：am start / move-stack 落地都是异步的。 */
        const val HEAL_VERIFY_MS = 400L

        /**
         * [recheckLayersAfterFill] 的等待：实车是 `am task resize` 之后约 1.6s 才
         * 观察到 DTA 被踢出层树（issue #19），1.5s 会早于故障发生，取 1.8s 留余量。
         */
        const val LAYER_RECHECK_DELAY_MS = 1_800L

        /** 自愈路径落盘的 display 段长度上限，见 [summarizeDisplay]。 */
        const val HEAL_DUMP_LOG_CHARS = 2000
    }
}
