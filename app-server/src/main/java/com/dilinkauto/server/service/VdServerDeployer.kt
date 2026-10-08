package com.dilinkauto.server.service

import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployExecutor
import com.dilinkauto.protocol.VdProbeResult
import com.dilinkauto.protocol.VideoConfig
import com.dilinkauto.protocol.vdRunDeploySequence
import com.dilinkauto.server.R
import com.dilinkauto.server.adb.RemoteAdbController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns deployment of the vd-server ([com.dilinkauto.vdserver.PipelineServer])
 * onto the phone from the car, via whichever ADB transport is currently
 * available (USB or TCP), plus the retry/fallback policy around it.
 *
 * Extracted from [CarConnectionService] so the connection state machine does
 * not own deploy logic, the retry counter, or the kill/launch command
 * assembly. The deployer reads viewport dims + DPI from the host (which owns
 * the display) and reports status through the host's message sink.
 *
 * The kill → wait-for-exit → launch orchestration itself now lives in
 * [vdRunDeploySequence] (protocol-core), shared with the phone's Shizuku path
 * and the desktop adb.exe path. This class only supplies:
 *  - the two entry points ([deploy] / [deployDirect]) and their retry policy;
 *  - a [VdDeployExecutor] implementation per transport;
 *  - the plan assembly (viewport + DPI) and status-message sequencing.
 */
internal class VdServerDeployer(private val host: CarConnectionService) {

    private var deployRetries = 0

    private fun log(msg: String, level: String = "I") = host.carLogSend(msg, level)

    /**
     * Deploy via whichever ADB transport [host] currently reports as ready.
     * Retries twice on transient "no ADB" then falls back to TCP-ADB using
     * the phone-host IP if known.
     */
    fun deploy() {
        if (host.vdServerStarted) return
        if (!host.isAdbAvailable()) {
            log("deployVdServer: no ADB connection (retry=$deployRetries)")
            if (deployRetries < 2) {
                deployRetries++
                host.scope.launch(Dispatchers.IO) {
                    delay(500)
                    deploy()
                }
                return
            }
            host.noAdbCount++
            // Auto-fallback: try TCP ADB if we have the phone IP
            val phoneHost = host.phoneHost
            if (phoneHost != null && !host.carPrefs.devMode) {
                log("Auto-fallback: trying TCP ADB to $phoneHost:${Ports.ADB_PORT}")
                host.setStatusMessage(R.string.status_connecting_tcp_adb, phoneHost)
                host.scope.launch(Dispatchers.IO) {
                    host.connectTcpAdb(phoneHost)
                    if (host.adbController?.isConnected == true) {
                        log("Auto-fallback TCP ADB connected — deploying VD server")
                        deploy()
                    } else {
                        host.setStatusMessage(R.string.status_no_adb)
                        log("Auto-fallback TCP ADB failed — connect phone to car USB", "W")
                    }
                }
            } else if (phoneHost == null) {
                host.setStatusMessage(R.string.status_no_phone_ip)
            } else {
                host.setStatusMessage(R.string.status_no_adb)
            }
            return
        }
        host.vdServerStarted = true  // Set early to prevent duplicate deploys
        host.scope.launch(Dispatchers.IO) {
            runSequence(adbExecutor(), host.vdServerJarPath)
        }
    }

    /**
     * Deploy using a known-good [controller] (the dev-mode TCP-ADB path, which
     * already has a fresh controller in hand). Bypasses the availability probe
     * because the controller is connected by construction.
     */
    suspend fun deployDirect(controller: RemoteAdbController) {
        if (host.vdServerStarted) return
        host.vdServerStarted = true  // Set early to prevent duplicate deploys
        runSequence(controllerExecutor(controller), VdDeploy.JAR_PATH)
    }

    /**
     * Shared body of both entry points: build the plan, run the standardized
     * kill → wait → force-kill-if-needed → launch sequence, and drive the
     * status messages. Failure re-opens [CarConnectionService.vdServerStarted]
     * so a later state-machine pass may retry.
     */
    private suspend fun runSequence(executor: VdDeployExecutor, jarPath: String) {
        val plan = buildPlan(jarPath)
        host.setStatusMessage(R.string.status_preparing_vd)
        val outcome = vdRunDeploySequence(plan, executor)
        host.setStatusMessage(R.string.status_starting_vd)
        if (!outcome.launched) {
            log("VD server failed to start", "E")
            host.setStatusMessage(R.string.status_vd_failed)
            host.vdServerStarted = false  // Allow retry
            return
        }
        host.setStatusMessage(R.string.status_waiting_video)
        log("VD server started, waiting for video")
    }

    /** Viewport + DPI resolution, shared by both entry points. */
    private fun buildPlan(jarPath: String): VdDeploy.DeployPlan {
        val displayMetrics = host.resources.displayMetrics
        val vp = CarViewport.size(
            displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.density
        )
        val vdW = vp.first
        val vdH = vp.second
        val phoneDpi = if (host.handshakeVdDpi > 0) host.handshakeVdDpi
            else VideoConfig.calculateOptimalDpi(vdW, vdH, displayMetrics.densityDpi)
        log("VD server: ${vdW}x${vdH}@${phoneDpi}dpi (car-native, no downscale)")
        return VdDeploy.buildDeployPlan(
            jarPath = jarPath,
            logPath = VdDeploy.LOG_PATH,
            vdWidth = vdW, vdHeight = vdH, dpi = phoneDpi,
            encodeWidth = vdW, encodeHeight = vdH,
            phoneHost = "127.0.0.1", fps = host.targetFps,
            bitrate = host.carPrefs.startupBitrate,
            // background=false → exec app_process，shell 流保持附着：
            // 带 & 后台化会让 shell 立即退出、ADB 流立刻关闭，而车机端
            // TcpAdbConnection 没有按流分发的读线程，残留消息会连带
            // 干掉刚启动的引擎（表现为日志 0 字节、进程秒死）。
            background = false
        )
    }

    // ─── Transports ───

    /** USB / local-ADB executor — commands go through the host's active transport. */
    private fun adbExecutor() = object : VdDeployExecutor {
        override suspend fun shellSync(command: String) {
            host.executeAdb(command, noWait = false)
        }

        override suspend fun launch(command: String): Boolean =
            host.executeAdb(command, noWait = true)

        override suspend fun probe(): VdProbeResult = probeViaHost()
    }

    /**
     * Dev-mode TCP-ADB executor — kill/launch go through the freshly established
     * [controller] (known-good by construction).
     */
    private fun controllerExecutor(controller: RemoteAdbController) = object : VdDeployExecutor {
        override suspend fun shellSync(command: String) {
            controller.shell(command)
        }

        override suspend fun launch(command: String): Boolean =
            controller.shellBackground(command) >= 0

        override suspend fun probe(): VdProbeResult = probeViaHost()
    }

    /**
     * Liveness probe via the host's currently-active ADB transport — this is
     * what the previous `waitForVdServerExit()` used on all car paths, so the
     * probe semantics are unchanged by the extraction.
     *
     * A `false` result means "probe exited non-zero" — gone when a transport is
     * up, but [VdProbeResult.UNKNOWN] when the transport itself died (`executeAdb`
     * documents `false` for an unavailable transport), so the sequence accepts
     * the exit instead of stalling the deploy.
     */
    private fun probeViaHost(): VdProbeResult {
        val alive = host.executeAdb(VdDeploy.probeExitCodeCommand, noWait = false)
        return when {
            alive -> VdProbeResult.ALIVE
            !host.isAdbAvailable() -> VdProbeResult.UNKNOWN
            else -> VdProbeResult.GONE
        }
    }

    /** Reset retry/state on a fresh connect cycle. */
    fun reset() {
        deployRetries = 0
    }
}
