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
 *  - [killCommand] — graceful `pkill -f PipelineServer` (lets the process
 *    clean up via its shutdown hook).
 *  - [killCommandForce] — `pkill -9 -f PipelineServer` for cases where the
 *    process is wedged and must be shot immediately (used by the phone-side
 *    display restorer before a power-on, where a slow shutdown would delay
 *    screen restore).
 */
object VdDeploy {
    const val MAIN_CLASS = "com.dilinkauto.vdserver.PipelineServer"

    /** Directory on shared storage where the JAR and log live. */
    const val DIR_PATH = "/sdcard/DiLinkAuto"
    const val JAR_NAME = "vd-server.jar"
    const val LOG_NAME = "vd-server.log"

    val JAR_PATH get() = "$DIR_PATH/$JAR_NAME"
    val LOG_PATH get() = "$DIR_PATH/$LOG_NAME"

    /** Graceful kill. Stderr suppressed because pkill returns non-zero when no match. */
    const val killCommand = "pkill -f PipelineServer 2>/dev/null"

    /** Force kill (-9). Use only when the process cannot shut down on its own. */
    const val killCommandForce = "pkill -9 -f PipelineServer 2>/dev/null"

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
        return "CLASSPATH=$jarPath ${prefix}app_process / $MAIN_CLASS $args >>$logPath 2>&1$amp"
    }

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
