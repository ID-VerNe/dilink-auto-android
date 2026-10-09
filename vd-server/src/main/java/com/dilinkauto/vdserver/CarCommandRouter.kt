package com.dilinkauto.vdserver

import com.dilinkauto.protocol.AppTargets
import com.dilinkauto.protocol.AppUninstalledMessage
import com.dilinkauto.protocol.ControlMsg
import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.LaunchAppMessage
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.requireComponentName
import com.dilinkauto.protocol.requirePackageName

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
 */
internal class CarCommandRouter(
    private val exec: (String) -> Unit,
    private val execOut: (String) -> String?,
    private val displayId: () -> Int,
    private val onStackEmpty: () -> Unit,
    private val setDisplayPower: (Boolean) -> Unit,
) {

    fun dispatch(f: FrameCodec.Frame) {
        PipeLog.log("Car command received: 0x${Integer.toHexString(f.messageType.toInt() and 0xFF)}")
        when (f.messageType) {
            ControlMsg.LAUNCH_APP -> launchApp(LaunchAppMessage.decode(f.payload).packageName)
            ControlMsg.GO_BACK -> { exec("input -d ${displayId()} keyevent 4"); checkStackEmpty() }
            ControlMsg.GO_HOME -> { exec("input -d ${displayId()} keyevent 3"); checkStackEmpty() }
            ControlMsg.GO_RECENT -> { exec("input -d ${displayId()} keyevent 187"); checkStackEmpty() }
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
                    execOut("am start --display ${displayId()} -n ${VdDeploy.shellQuote(safeComponent)}")
                } else null
            } else {
                PipeLog.log("launchApp: resolve raw='${raw ?: "<null>"}' not a component — fallback to implicit intent")
                execOut("am start --display ${displayId()} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $quotedPkg")
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
            val match = Regex("ActivityRecord\\{[^ ]+ [^ ]+ ([^/ ]+/[^ } ]+) t(\\d+)\\}").find(sec)
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

    private companion object {
        /**
         * 手机控制端包名，从跨模块常量派生（见 [AppTargets] 的说明：applicationId
         * 变更时集中在这里改，不在各模块散落硬编码）。[moveTopApp] 用它跳过
         * 把控制端自身搬进 VD —— 理由见那段的注释。
         */
        val SELF_PACKAGE = AppTargets.PHONE_MAIN_ACTIVITY.substringBefore('/')
    }
}
