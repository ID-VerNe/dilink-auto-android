package com.dilinkauto.protocol

/**
 * Shared constants and command builders for deploying the vd-server
 * ([com.dilinkauto.vdserver.PipelineServer]) process.
 *
 * Three deploy sites build the same kill command + app_process line; keeping
 * them here means a change to the classpath layout, the main class, or the
 * kill flag lands in one place. The argv tail is built by [VdDeployArgs].
 *
 * The kill command comes in two flavors:
 *  - [killCommand] — graceful `pkill -f` (SIGTERM, lets the process clean up
 *    via its shutdown hook).
 *  - [killCommandForce] — `pkill -9 -f` for cases where the process is wedged
 *    and must be shot immediately.
 *
 *  - [stopCommand] — the two-stage "SIGTERM, wait, then SIGKILL" sequence used
 *    when tearing a session down. It exists as ONE shell line on purpose: the
 *    caller gets it back from a single [com.dilinkauto.client.ShizukuManager.execAndWait],
 *    so a coroutine cancellation (Service.onDestroy cancels serviceScope) can
 *    never land between the two signals and leave the process half-killed with
 *    cleanup() unrun.
 *
 * All patterns use the `[P]ipelineServer` bracket trick. A plain
 * `PipelineServer` pattern makes `pkill -f` match the *wrapper shell itself*
 * (`sh -c "pkill -f PipelineServer ..."` has that string in its own cmdline),
 * so the shell gets SIGTERM'd mid-script and any command after it in the same
 * line silently never runs. The bracket form matches the engine but not the
 * literal `[P]ipelineServer` text in the shell's cmdline.
 */
object VdDeploy {
    const val MAIN_CLASS = "com.dilinkauto.vdserver.PipelineServer"

    /**
     * Regex that matches the vd-server process but NOT the literal
     * `[P]ipelineServer` text inside the wrapper shell's own cmdline.
     * See the class doc for why the plain name is unsafe.
     */
    const val PROCESS_PATTERN = "[P]ipelineServer"

    /** Directory on shared storage where the JAR and log live. */
    const val DIR_PATH = "/sdcard/DiLinkAuto"
    const val JAR_NAME = "vd-server.jar"
    const val LOG_NAME = "vd-server.log"

    val JAR_PATH get() = "$DIR_PATH/$JAR_NAME"
    val LOG_PATH get() = "$DIR_PATH/$LOG_NAME"

    /** Graceful kill (SIGTERM). Stderr suppressed because pkill returns non-zero when no match. */
    const val killCommand = "pkill -f $PROCESS_PATTERN 2>/dev/null"

    /** Force kill (-9). Use only when the process cannot shut down on its own. */
    const val killCommandForce = "pkill -9 -f $PROCESS_PATTERN 2>/dev/null"

    /**
     * Two-stage stop: SIGTERM → wait 1s → SIGKILL, as a single shell line.
     *
     * This is the only stop path the display restorer uses. The previous
     * `pkill -9` skipped the JVM shutdown hook entirely, so `PipelineServer.cleanup()`
     * (which releases the VirtualDisplay, restores IME, resets letterbox and
     * re-powers the physical panel) never ran on most teardowns — each
     * exit/reconnect cycle leaked one VirtualDisplay plus a permanently
     * power-off physical panel, which is what turns the car screen black after
     * a few reconnects.
     */
    const val stopCommand =
        "pkill -f $PROCESS_PATTERN 2>/dev/null; sleep 1; pkill -9 -f $PROCESS_PATTERN 2>/dev/null; exit 0"

    /**
     * Liveness probe, output form: prints `Y` when a vd-server process exists,
     * `N` otherwise. Uses `pkill -0` (signal 0 = existence check only, no
     * signal sent) so it needs no `ps`/`pidof` (whose name matching breaks on
     * app_process, where the comm name is truncated). For callers that can read
     * stdout (the phone's Shizuku path).
     */
    const val probeCommand =
        "if pkill -0 -f $PROCESS_PATTERN >/dev/null 2>&1; then echo Y; else echo N; fi"

    /**
     * Liveness probe, exit-code form: exit 0 = alive, exit 1 = gone.
     * For callers that only see an exit status (the car's ADB `shell()` path).
     */
    const val probeExitCodeCommand =
        "if pkill -0 -f $PROCESS_PATTERN >/dev/null 2>&1; then exit 0; else exit 1; fi"

    /**
     * Build the full app_process command line.
     *
     * @param jarPath  CLASSPATH argument (location of vd-server.jar).
     * @param logPath  where stdout+stderr are redirected.
     * @param args     argv tail from [VdDeployArgs.format].
     * @param background when false, the line uses `exec` so app_process replaces
     *   the caller's shell — used by the car USB/TCP-ADB paths, where the ADB
     *   stream must stay attached to the engine process (the car's
     *   TcpAdbConnection has no stream-demux reader; a closing stream kills the
     *   just-started engine). When true, the line uses `setsid ... &` so
     *   app_process detaches into its own session — used by the phone Shizuku
     *   path, where `execBackground` is fire-and-forget and the engine must
     *   survive the parent sh's exit (and Shizuku's process tracking, which is
     *   tied to the parent sh PID). `setsid` is what actually detaches: with
     *   plain `exec app_process ... &`, the engine remained in Shizuku's
     *   process group and could be reaped when the parent sh exited, leaving
     *   vd-server.log at 0 bytes.
     */
    fun commandLine(jarPath: String, logPath: String, args: String, background: Boolean): String {
        val prefix = if (background) "setsid " else "exec "
        val amp = if (background) " &" else ""
        // Append (>>), not truncate (>): a reconnect storm runs pkill + restart
        // several times in quick succession, and each restart reopens the log.
        // With truncation the LAST restart's process — which often dies before its
        // first stdout flush — erases the FIRST failure's diagnostic output,
        // leaving a 0-byte log. Append preserves every run's output in order.
        //
        // 路径一律经 [shellQuote]（audit WIN-09）：`jarPath` 来自**对端**（握手响应的
        // `vdServerJarPath`），原样拼进设备侧 shell 就是把"对端可控字符串"喂给
        // `sh -c`（执行身份 shell）。三条部署路径最后都是 shell，所以单引号在这里
        // 是通用且必要的。`logPath` 目前只来自本端常量，同样加上以免日后被人接到参数上。
        return "CLASSPATH=${shellQuote(jarPath)} ${prefix}app_process / $MAIN_CLASS $args " +
            ">>${shellQuote(logPath)} 2>&1$amp"
    }

    /**
     * POSIX shell 单引号引用（audit WIN-09）。
     *
     * 单引号内除 `'` 本身外不解释任何字符，所以只需把内嵌的 `'` 拆成 `'\''`
     * （闭合 → 转义单引号 → 重新打开）。用于任何"字符串要进设备侧 shell"的位置。
     */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /**
     * A fully-assembled VD-server deploy plan: the argv tail, the kill command,
     * and the launch command line. Built once from viewport + DPI + fps by
     * [buildDeployPlan] and handed to whichever executor is available
     * (car USB ADB, car TCP ADB, or phone Shizuku) so the three deploy sites
     * do not each re-assemble the same sequence.
     */
    data class DeployPlan(
        val args: String,
        val killCommand: String,
        val launchCommand: String
    )

    /**
     * Build a [DeployPlan] from the viewport dimensions, phone DPI, and fps.
     *
     * @param jarPath   vd-server.jar location (caller-selected: VdDeploy.JAR_PATH
     *                  for the car paths, or a Shizuku-resolved path on the phone).
     * @param logPath   vd-server.log location.
     * @param vdWidth    VirtualDisplay width (car-native, even-aligned).
     * @param vdHeight   VirtualDisplay height (car-native, even-aligned).
     * @param dpi        DPI to use (caller-resolved: handshake echo or auto-calibrated).
     * @param encodeWidth encoder width (clamped to 1920 by VdDeployArgs).
     * @param encodeHeight encoder height (clamped to 1080 by VdDeployArgs).
     * @param fps        target frame rate.
     * @param background when true, the launch command backgrounds the server.
     */
    fun buildDeployPlan(
        jarPath: String,
        logPath: String,
        vdWidth: Int,
        vdHeight: Int,
        dpi: Int,
        encodeWidth: Int,
        encodeHeight: Int,
        phoneHost: String,
        fps: Int,
        bitrate: Int = VideoConfig.DEFAULT_BITRATE,
        background: Boolean
    ): DeployPlan {
        val args = VdDeployArgs.format(vdWidth, vdHeight, dpi, phoneHost, encodeWidth, encodeHeight, fps, bitrate)
        return DeployPlan(
            args = args,
            killCommand = killCommand,
            launchCommand = commandLine(jarPath, logPath, args, background)
        )
    }
}
