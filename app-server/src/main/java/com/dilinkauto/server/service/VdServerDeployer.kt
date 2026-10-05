package com.dilinkauto.server.service

import com.dilinkauto.protocol.Discovery
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VideoConfig
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
 * Two entry points:
 *  - [deploy] — runs the availability check, retry, and TCP-ADB fallback.
 *    Called from the state machine when both tracks are ready or after a
 *    USB-ADB connection completes post-handshake.
 *  - [deployDirect] — for the dev-mode TCP-ADB path, which already has a
 *    fresh [RemoteAdbController] in hand and wants to deploy immediately
 *    without the availability probe (the controller is known-good).
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
            if (phoneHost != null && !host.devMode) {
                log("Auto-fallback: trying TCP ADB to $phoneHost:${Discovery.ADB_PORT}")
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
            val displayMetrics = host.resources.displayMetrics
            val vp = CarConnectionService.getViewportSize(
                displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.density
            )
            val vdW = vp.first
            val vdH = vp.second
            val phoneDpi = if (host.handshakeVdDpi > 0) host.handshakeVdDpi
                else VideoConfig.calculateOptimalDpi(vdW, vdH, displayMetrics.densityDpi)

            val plan = VdDeploy.buildDeployPlan(
                jarPath = host.vdServerJarPath,
                logPath = VdDeploy.LOG_PATH,
                vdWidth = vdW, vdHeight = vdH, dpi = phoneDpi,
                encodeWidth = vdW, encodeHeight = vdH,
                phoneHost = "127.0.0.1", fps = host.targetFps,
                bitrate = host.startupBitrate,
                // background=false → exec app_process，shell 流保持附着：
                // 带 & 后台化会让 shell 立即退出、ADB 流立刻关闭，而车机端
                // TcpAdbConnection 没有按流分发的读线程，残留消息会连带
                // 干掉刚启动的引擎（表现为日志 0 字节、进程秒死）。
                background = false
            )

            // Kill any existing VD server
            host.setStatusMessage(R.string.status_preparing_vd)
            host.executeAdb(plan.killCommand, noWait = false)
            // Wait for a real exit before launching the replacement. Two live
            // engines race for the same VirtualDisplay / DTA / 9638-9639 binds
            // and the loser never runs cleanup() → one leaked VD per reconnect,
            // which is what turns the car screen black after a few cycles.
            if (!waitForVdServerExit()) {
                log("Previous VD server did not exit in time — forcing kill", "W")
                host.executeAdb(VdDeploy.stopCommand, noWait = false)
                waitForVdServerExit()
            }

            // Launch VD server. Uses exec to replace shell with app_process — keeps ADB stream open.
            // VD server will die on disconnect; car re-deploys on reconnect.
            host.setStatusMessage(R.string.status_starting_vd)
            log("VD server: ${vdW}x${vdH}@${phoneDpi}dpi (car-native, no downscale)")

            if (!host.executeAdb(plan.launchCommand, noWait = true)) {
                log("VD server failed to start", "E")
                host.setStatusMessage(R.string.status_vd_failed)
                return@launch
            }

            host.vdServerStarted = true
            host.setStatusMessage(R.string.status_waiting_video)
            log("VD server started, waiting for video")
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
        val displayMetrics = host.resources.displayMetrics
        val vp = CarConnectionService.getViewportSize(
            displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.density
        )
        val vdW = vp.first
        val vdH = vp.second
        val phoneDpi = if (host.handshakeVdDpi > 0) host.handshakeVdDpi
            else VideoConfig.calculateOptimalDpi(vdW, vdH, displayMetrics.densityDpi)
        // Encode dims clamped to 1920x1080 (Snapdragon 439 VPU hardware-decode cap).
        val plan = VdDeploy.buildDeployPlan(
            jarPath = VdDeploy.JAR_PATH,
            logPath = VdDeploy.LOG_PATH,
            vdWidth = vdW, vdHeight = vdH, dpi = phoneDpi,
            encodeWidth = vdW, encodeHeight = vdH,
            phoneHost = "127.0.0.1", fps = host.targetFps,
            bitrate = host.startupBitrate,
            // 同 deploy()：必须 exec 保持流附着，不能用 & 后台化
            background = false
        )
        host.setStatusMessage(R.string.status_preparing_vd)
        log("VD server: ${vdW}x${vdH}@${phoneDpi}dpi (car-native, no downscale)")
        // Use shell (sync) to capture result. pkill old instance first, then start new one.
        controller.shell(plan.killCommand)
        // Wait for a real exit — two live engines race for the same
        // VirtualDisplay / DTA / 9638-9639 binds and the loser never cleans up.
        if (!waitForVdServerExit()) {
            log("Previous VD server did not exit in time — forcing kill", "W")
            controller.shell(VdDeploy.stopCommand)
            waitForVdServerExit()
        }
        // shellBackground 打开流后不关闭：exec app_process 接管 shell，
        // 流在引擎存活期间一直附着，进程不会被 adbd 回收
        val streamId = controller.shellBackground(plan.launchCommand)
        val ok = streamId >= 0
        host.setStatusMessage(R.string.status_starting_vd)
        if (ok) {
            log("VD server started, waiting for video")
        } else {
            log("VD server failed to start", "E")
            host.vdServerStarted = false  // Allow retry
        }
    }

    /**
     * Poll the phone over ADB until no vd-server process remains.
     *
     * Uses `pkill -0` (existence check, no signal delivered) because `ps`/
     * `pidof` name matching is unreliable for `app_process`-launched engines
     * (the comm name is truncated). The probe encodes the result in its exit
     * code, which is all the ADB `shell()` path exposes. Returns true when the
     * engine is gone or we cannot probe (no ADB) — in the latter case we proceed
     * rather than stall the deploy.
     */
    private suspend fun waitForVdServerExit(timeoutMs: Long = 3000, pollMs: Long = 150): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var probeFailed = false
        while (System.currentTimeMillis() < deadline) {
            if (!host.executeAdb(VdDeploy.probeExitCodeCommand, noWait = false)) {
                // shell() reports the command's exit status; false here means
                // "no process matched" (exit 1) — engine is gone. A transport
                // failure also returns false, so distinguish by retrying once:
                // if the next probe also reports gone, accept it.
                if (!host.isAdbAvailable()) return true
                if (!probeFailed) {
                    probeFailed = true
                    delay(pollMs)
                    continue
                }
                return true
            }
            delay(pollMs) // exit 0 = still alive
        }
        log("vd-server still alive after ${timeoutMs}ms wait", "W")
        return false
    }

    /** Reset retry/state on a fresh connect cycle. */
    fun reset() {
        deployRetries = 0
    }
}
