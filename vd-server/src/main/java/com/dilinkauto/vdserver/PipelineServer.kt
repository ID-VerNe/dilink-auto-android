package com.dilinkauto.vdserver

import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.dilinkauto.protocol.*
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * VD server entry point and lifecycle owner.
 *
 * The body is split into focused collaborators:
 *  - [GlPipeline] — EGL/GLES render + MediaCodec encode loop (pipeline thread).
 *  - [TouchInjector] — InputManager reflection + multi-touch state.
 *  - [DisplayPowerController] — physical panel power + IME policy/restore.
 *  - [VirtualDisplayCreator] — VirtualDisplay creation + environment overrides.
 *  - [PersistentShell] — the long-lived `sh` every command goes through.
 *  - [CarCommandRouter] — control-channel commands + display queries.
 *  - [LifecycleWriter] — the single-writer thread for lifecycle responses.
 *
 * This class owns: process lifecycle, encoder setup, socket bind/accept, the
 * lifecycle/touch reader threads, the watchdog, and cleanup ordering. It runs
 * as shell via `app_process`.
 *
 * Threading contract (audit S-06 / S-M8 / S-M9):
 *  - The pipeline thread is a **daemon** and is the only thread allowed to touch
 *    EGL/GL — the context was made current there. It waits for its go-ahead in
 *    bounded `parkNanos` slices so a failed `bindAndAccept` cannot park it
 *    forever and leave a zombie shell-UID process holding the VirtualDisplay and
 *    both binds.
 *  - `cleanup()` runs on main / Lifecycle / TouchReader / watchdog. It *requests*
 *    the pipeline thread's exit (`running=false` + `unpark` + bounded join) and
 *    never destroys EGL itself; the pipeline thread's own `finally` does that,
 *    guarded by `GlPipeline.glTornDown`.
 *  - `inputSurfaceReady` is counted down in a `finally` on the pipeline thread and
 *    awaited with a timeout on the main thread, and the watchdog is armed only
 *    after that await — so an EGL/GL init failure can no longer wedge the main
 *    thread while leaving `running` true (which the watchdog could not act on).
 */
class PipelineServer(
    private val displayWidth: Int, private val displayHeight: Int, private val dpi: Int,
    private val phoneHost: String, private val encodeWidth: Int, private val encodeHeight: Int,
    fps: Int, private val bitrate: Int = BITRATE,
    /**
     * S-01: receiver IP to pin 9638/9639 accepts to. null = accept any peer
     * (legacy behaviour for deployers that cannot determine their own outbound
     * address). The engine runs as shell UID with otherwise unauthenticated
     * ports, so anything that CAN determine it must pass it.
     */
    private val carHost: String? = null
) {
    /**
     * S-L2：fps 来自 argv，可能是遗留/被手改的 pref（含 0）。`1_000_000_000L / 0`
     * 会在构造器里抛 ArithmeticException，进程连 bind 都没到就死，所以先钳进
     * protocol-core 里唯一的那份 FPS 边界（[VideoConfig.MIN_FPS]/[VideoConfig.MAX_FPS]）。
     */
    private val fps = coerceFps(fps)
    // `this.fps` is deliberate: inside a property initializer the bare name `fps`
    // still resolves to the constructor parameter (uncoerced), which would reintroduce
    // the divide-by-zero this guard exists to prevent.
    private val frameIntervalNanos = 1_000_000_000L / this.fps
    @Volatile private var running = true
    private val cleanedUp = AtomicBoolean(false)
    private var displayId = -1
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null

    /** Pipeline 线程引用：cleanup() 靠它 unpark + 有界 join（S-06/S-M8）。 */
    @Volatile private var pipelineThread: Thread? = null

    private val shell = PersistentShell()

    // SurfaceTexture + VD surface (created on pipeline thread, used by VD)
    private var vdInputSurface: android.view.Surface? = null
    private var encoderSurface: android.view.Surface? = null

    private val touchInjector = TouchInjector(
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        displayIdProvider = { displayId },
        shellProvider = { shell.input },
        logErr = { err(it) }
    )
    private val displayController = DisplayPowerController(
        shellProvider = { shell.input },
        execShellOutput = { shell.execOutput(it) },
        logErr = { err(it) }
    )
    private val vdCreator = VirtualDisplayCreator(
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        dpi = dpi,
        execShell = { shell.exec(it) },
        execShellOutput = { shell.execOutput(it) },
        log = { log(it) },
        err = { err(it) }
    )
    private val lifecycle = LifecycleWriter { running }
    private val carCommands = CarCommandRouter(
        exec = { shell.exec(it) },
        execOut = { shell.execOutput(it) },
        displayId = { displayId },
        onStackEmpty = { lifecycle.enqueue(VdLifecycle.MSG_STACK_EMPTY, ByteArray(0)) },
        setDisplayPower = { displayController.setPhysicalDisplayPower(it) }
    )
    private lateinit var glPipeline: GlPipeline

    // Synchronization between main thread and pipeline thread
    private val inputSurfaceReady = CountDownLatch(1)
    @Volatile private var carVideoChannel: SocketChannel? = null

    companion object {
        private const val BITRATE = VideoConfig.DEFAULT_BITRATE // alias — bitrate bounds live in VideoConfig
        private const val I_FRAME_INTERVAL = 1
        private const val WATCHDOG_GRACE_MS = 3000L
        private const val SOCKET_BUF_BYTES = Connection.SOCKET_BUF_BYTES // single source: protocol-core

        /** S-M9：主线程等 EGL/GL 初始化的上限。超时即致命，绝不久久挂起。 */
        private const val INPUT_SURFACE_READY_TIMEOUT_SECONDS = 10L

        /**
         * S-06：Pipeline 线程等主线程建 VD/accept 的 park 粒度。
         *
         * 原来是无限期的 `LockSupport.park()`：没人 unpark 时永久挂住，而 running=false
         * 对已 park 的线程毫无作用。改成 parkNanos 循环后最坏一个轮询周期即可退出。
         */
        private const val PARK_POLL_NANOS = 100_000_000L

        /** S-M8：cleanup() 等 Pipeline 线程退出的上限；超时不阻塞，宁可泄漏 EGL。 */
        private const val PIPELINE_JOIN_TIMEOUT_MS = 2000L

        /** S-L2：把任意 fps（含 0/负数/荒谬值）钳进 [VideoConfig.MIN_FPS]..[VideoConfig.MAX_FPS]。 */
        fun coerceFps(fps: Int): Int = fps.coerceIn(VideoConfig.MIN_FPS, VideoConfig.MAX_FPS)

        @JvmStatic fun main(args: Array<String>) {
            AndroidPlatformHooks.install()
            val w = args.getOrNull(0)?.toInt() ?: 1408; val h = args.getOrNull(1)?.toInt() ?: 792
            val d = args.getOrNull(2)?.toInt() ?: 120; val ph = args.getOrNull(3) ?: "127.0.0.1"
            val ew = args.getOrNull(4)?.toInt() ?: w; val eh = args.getOrNull(5)?.toInt() ?: h
            val f = args.getOrNull(6)?.toInt() ?: VideoConfig.TARGET_FPS
            val br = args.getOrNull(7)?.toInt() ?: BITRATE
            // S-01: peer pinning. The engine runs as shell UID and binds
            // 0.0.0.0:9638/9639 with no other authentication, so the deployer
            // passes the receiver IP it is serving; accepts from any other host
            // are refused. `-`/absent keeps the legacy accept-any behaviour.
            val carHost = args.getOrNull(8)?.takeIf { it.isNotBlank() && it != VdDeployArgs.CAR_HOST_ANY }
            log("Starting: VD=${w}x${h} @${d}dpi, encode=${ew}x${eh}, phoneHost=$ph, fps=$f, bitrate=${br/1_000_000}M, carHost=${carHost ?: "ANY"}")
            PipelineServer(w, h, d, ph, ew, eh, f, br, carHost).run()
        }
        private fun log(msg: String) = PipeLog.log(msg)
        private fun err(msg: String) = PipeLog.err(msg)
    }

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread({ cleanup() }, "ShutdownHook"))
        // 删除上一轮可能残留的停止哨兵（见 [VdDeploy.gracefulStopCommand] 的协议）：
        // 场景——外部写了请求、引擎未及消费即被强杀，文件残留；下一次 launch
        // 的新引擎必须忽略它，否则会"一启动就被上一轮的旧信号误杀"。
        runCatching { File(VdDeploy.STOP_REQUEST_PATH).delete() }
        try {
            touchInjector.initInputManager()
            shell.start()
            try { setupEncoder() } catch (e: Exception) { err("Fatal: encoder: ${e.message}"); return }
            // setupEncoder 成功时 encoder/encoderSurface 已是一对可用的
            // create→configure→createInputSurface→start 实例（见其 KDoc）。
            if (encoder == null || encoderSurface == null) return

            glPipeline = GlPipeline(
                displayWidth, displayHeight, encodeWidth, encodeHeight, fps,
                frameIntervalNanos, bitrate
            ).also { it.encoderSurface = encoderSurface }

            // Start pipeline thread — it initializes EGL/GL and signals when VD input surface is ready.
            //
            // S-06: must be a daemon. It used to be a plain thread, so a pipeline thread
            // parked in LockSupport.park() forever (see runPipeline) kept the shell-UID
            // JVM — and with it the VirtualDisplay and both 0.0.0.0 binds — alive.
            val t = Thread({ runPipeline() }, "Pipeline").apply { isDaemon = true }
            pipelineThread = t
            t.start()
            // S-M9: bounded wait. runPipeline countDown()s in a finally, so an EGL/GL init
            // failure surfaces here as `false` instead of parking the main thread forever
            // while running stayed true and the watchdog's outer loop never exited.
            val ready = try { inputSurfaceReady.await(INPUT_SURFACE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { false }
            if (!ready) {
                running = false
                err("Fatal: EGL/GL init did not signal within ${INPUT_SURFACE_READY_TIMEOUT_SECONDS}s")
                return
            }
            // S-M9: arm the watchdog only now, i.e. after the main thread is guaranteed to
            // be unblocked. Armed before the await it could only observe running=true.
            startWatchdog()
            if (!createVirtualDisplay()) { running = false; err("Fatal: failed to create VD"); return }
            val conns = bindAndAccept() ?: run { running = false; return }
            startLifecycleReader(conns.phoneChannel)
            startTouchReader(conns.carInput)
            lifecycle.start()
            // Signal pipeline to begin rendering with the car video channel
            carVideoChannel = conns.carVideo
            LockSupport.unpark(t)
            try { t.join() } catch (_: InterruptedException) {}
        } finally {
            cleanup()
        }
    }

    /**
     * 选一个**真正能跑**的 H.264 编码器并完成 start（成功时 [encoder]/[encoderSurface]
     * 为一对可用实例）。
     *
     * 旧写法是 `createEncoderByType()` 盲取框架排序里的第一个 video/avc 编码器。
     * 2026-10-10 MI 9（Android 15 移植 ROM）实机缺陷：该 ROM 的 media_codecs.xml
     * 把遗留组件 `OMX.qcom.video.encoder.avc` 排在 `c2.qti.avc.encoder` 之前，而前者
     * 在 configure 时抛 `CodecException`（message 为空；logcat 真因
     * `venc_dev: Unsupported eColorFormat 0x7f000789`，即不支持 COLOR_FormatSurface）
     * —— 采集管线整体起不来，而空消息让 vd-server.log 只剩一行 "Fatal: encoder: "。
     *
     * 现在按 [orderEncoderCandidates] 排序后逐个 create → configure →
     * createInputSurface → start，第一个成功者胜出；全部失败时抛出的异常**聚合每个
     * 候选与失败原因**（可直接从 vd-server.log 定位，不再依赖 logcat）。
     */
    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
        format.setInteger(MediaFormat.KEY_LATENCY, 0); format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0); format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        format.setLong("repeat-previous-frame-after", 500_000L)

        val tried = mutableListOf<String>()
        for (name in orderEncoderCandidates(avcEncoderCandidates())) {
            try {
                val codec = MediaCodec.createByCodecName(name)
                try {
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoderSurface = codec.createInputSurface()
                    codec.start()
                } catch (e: Exception) {
                    // 半成品实例必须释放：失败的 codec 已占用底层组件（真机上
                    // OMX.qcom 的失败实例不释放会占住 concurrent-instances 名额）。
                    runCatching { codec.release() }
                    throw e
                }
                encoder = codec
                log(
                    "Encoder: $name ${encodeWidth}x${encodeHeight} CBR@${bitrate / 1_000_000}Mbps Main ${fps}fps" +
                        if (tried.isEmpty()) "" else "（跳过不可用: ${tried.joinToString(", ")}）"
                )
                return
            } catch (e: Exception) {
                // 失败原因保留在聚合信息里；空 message 的 CodecException 用类名兜底。
                val why = e.message?.trim().takeUnless { it.isNullOrEmpty() } ?: e.javaClass.simpleName
                tried += "$name($why)"
            }
        }
        throw IOException("没有可用的 H.264 编码器；候选与失败原因: ${tried.joinToString("; ")}")
    }

    /**
     * 枚举框架里所有 video/avc 编码器（名字 + 是否纯软实现），保留框架原顺序。
     *
     * 用 `ALL_CODECS`：本缺陷的教训正是"框架排序里的第一个"不可靠 —— 拿全量列表
     * 自己排序 + 逐个真尝试（见 [orderEncoderCandidates] 与 [setupEncoder]）。
     */
    private fun avcEncoderCandidates(): List<Pair<String, Boolean>> {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val out = ArrayList<Pair<String, Boolean>>(infos.size)
        for (info in infos) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }) continue
            out += info.name to info.isSoftwareOnly
        }
        return out
    }

    // ── VD Creation ──

    private fun createVirtualDisplay(): Boolean {
        val vdSurf = vdInputSurface ?: return false
        // Snapshot the system state we are about to change — BEFORE changing it.
        // This used to run after VirtualDisplayCreator.create(), which itself
        // wrote screen_off_timeout=SessionScreenTimeout.SESSION_TIMEOUT_SENTINEL /
        // lift_wakeup=0 / proximity=0, so the "original" snapshot captured our own
        // writes and restoreIme() could never put the user's real values back.
        displayController.saveCurrentIme()
        virtualDisplay = vdCreator.create(vdSurf)
        if (virtualDisplay == null) return false
        displayId = vdCreator.displayId
        // Now that the originals are safely stored, apply the session overrides.
        vdCreator.configureEnvironment()
        try { displayController.setDisplayImePolicy(displayId) } catch (_: Exception) {}
        return true
    }

    // ── Connection ──

    data class ConnectionSet(val carVideo: SocketChannel, val carInput: SocketChannel, val phoneChannel: SocketChannel)

    private fun bindAndAccept(): ConnectionSet? {
        val videoServer: ServerSocketChannel; val inputServer: ServerSocketChannel
        try {
            videoServer = ServerSocketChannel.open(); videoServer.configureBlocking(false); videoServer.socket().reuseAddress = true
            videoServer.socket().bind(InetSocketAddress("0.0.0.0", Ports.VIDEO_PORT)); log("Video on :${Ports.VIDEO_PORT}")
            inputServer = ServerSocketChannel.open(); inputServer.configureBlocking(false); inputServer.socket().reuseAddress = true
            inputServer.socket().bind(InetSocketAddress("0.0.0.0", Ports.INPUT_PORT)); log("Input on :${Ports.INPUT_PORT}")
        } catch (e: Exception) { err("Bind: ${e.message}"); return null }
        // S-06: the two listening sockets are closed in ONE finally around the whole body.
        //
        // They used to be closed per-branch, and the accept-timeout branches only closed
        // the phone channel. A 30s video/input timeout therefore returned null with
        // 0.0.0.0:9638 and 0.0.0.0:9639 still bound by a zombie shell-UID process —
        // which also still held the VirtualDisplay and the applied panel/letterbox
        // state — so the next session could not bind and only a force-stop helped.
        try {
            val phoneChannel = connectToPhoneHost() ?: return null
            try { sendDisplayReady(phoneChannel); log("Display ready sent") } catch (e: Exception) { err("Display ready: ${e.message}"); try { phoneChannel.close() } catch (_: Exception) {}; return null }
            lifecycle.channel = phoneChannel
            shell.exec("input keyevent 224"); log("Waking up device to ensure VD activities resume")
            shell.exec("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.HOME"); log("Home launched")
            carCommands.moveTopApp(0, displayId)
            // 校验 DTA 是否挂在 display 层树内（Flyme 偶发 reparent 事务丢失 → 0 层黑屏），必要时旋转往返修复
            vdCreator.ensureDtaAttached()
            // Physical panel power-off happens *after* the car connects — see below.
            val carVideo = acceptCarChannel(videoServer, 30000) ?: run { err("Car video timeout"); try { phoneChannel.close() } catch (_: Exception) {}; return null }
            val carInput = acceptCarChannel(inputServer, 30000) ?: run { err("Car input timeout"); try { phoneChannel.close() } catch (_: Exception) {}; try { carVideo.close() } catch (_: Exception) {}; return null }
            log("Car connected: video=${carVideo.remoteAddress} input=${carInput.remoteAddress}")
            // Power the physical panel off only now — after the car holds both channels.
            //
            // This used to run *before* the accepts, i.e. possibly facing a 30s
            // accept timeout, which produced two failure modes:
            //  - a slow car found the phone already dark, so the user stared at a
            //    black phone before anything was actually broken;
            //  - being killed during that window meant cleanup() had to re-power a
            //    panel we had switched off for a session that never started, and any
            //    interruption of cleanup left it off permanently.
            // Now the panel only goes dark once a live stream exists to replace it,
            // and a car that fails to connect never costs the user their screen.
            displayController.setPhysicalDisplayPower(false)
            return ConnectionSet(carVideo, carInput, phoneChannel)
        } finally {
            try { videoServer.close() } catch (_: Exception) {}
            try { inputServer.close() } catch (_: Exception) {}
        }
    }

    private fun connectToPhoneHost(): SocketChannel? {
        val addr = InetSocketAddress(phoneHost, Ports.LIFECYCLE_PORT)
        for (attempt in 0 until 60) {
            if (!running) break
            var ch: SocketChannel? = null
            try {
                ch = SocketChannel.open(); ch.configureBlocking(false); ch.connect(addr)
                val deadline = System.currentTimeMillis() + 2000
                while (!ch.finishConnect()) { if (!running || System.currentTimeMillis() > deadline) { ch.close(); throw ConnectException("timeout") }; Thread.sleep(50) }
                ch.configureBlocking(false); ch.socket().tcpNoDelay = true; return ch
            } catch (e: ConnectException) { ch?.close(); if (attempt < 59) Thread.sleep(200) }
            catch (e: Exception) { ch?.close(); err("Lifecycle connect: ${e.message}"); break }
        }
        return null
    }

    private fun sendDisplayReady(ch: SocketChannel) {
        val hasInjection = touchInjector.hasInjection
        val buf = ByteBuffer.allocate(6); buf.put(VdLifecycle.MSG_DISPLAY_READY); buf.putInt(displayId); buf.put(if (hasInjection) 1 else 0); buf.flip()
        ch.configureBlocking(true); while (buf.hasRemaining()) ch.write(buf); ch.configureBlocking(false)
    }

    private fun acceptCarChannel(server: ServerSocketChannel, timeoutMs: Int): SocketChannel? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            val a = server.accept()
            if (a != null) {
                // S-01: refuse peers that are not the receiver we are serving. The
                // first accept used to win unconditionally, so any host on the
                // phone's WiFi could drive touch injection and am start/pm
                // uninstall as shell UID. A refused peer is closed and we keep
                // waiting for the real one until the timeout.
                if (!isAllowedPeer(a)) {
                    err("Refused car channel from ${a.remoteAddress} (expected ${carHost ?: "ANY"})")
                    try { a.close() } catch (_: Exception) {}
                    continue
                }
                a.configureBlocking(false)
                val s = a.socket()
                s.sendBufferSize = SOCKET_BUF_BYTES
                s.receiveBufferSize = SOCKET_BUF_BYTES
                s.tcpNoDelay = true
                return a
            }
            Thread.sleep(50)
        }
        return null
    }

    /** Host part of the accepted socket's remote address, or null when unknown. */
    private fun remoteHostOf(ch: SocketChannel): String? = try {
        (ch.remoteAddress as? InetSocketAddress)?.address?.hostAddress
    } catch (_: Exception) { null }

    private fun isAllowedPeer(ch: SocketChannel): Boolean {
        val expected = carHost ?: return true // no pinning requested
        val actual = remoteHostOf(ch) ?: return false
        return actual == expected
    }

    // ── Pipeline thread ──

    private fun runPipeline() {
        try {
            // Encoder + GL pipeline thread; mark urgent so background threads
            // (LifeWriter, watchdog) don't preempt the encode path on A53.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var eglReady = false
            try {
                glPipeline.initEglAndSurfaceTexture()
                vdInputSurface = glPipeline.vdSurface()
                eglReady = true
            } finally {
                // S-M9: countDown moved into the finally. It only used to run on the success
                // path, so an EGL/GL init exception left the main thread in a forever await()
                // with running==true — the one combination the watchdog cannot act on.
                inputSurfaceReady.countDown()
            }
            if (eglReady) {
                // Wait for main thread to create VD, accept connections, and set carVideoChannel.
                //
                // S-06: this was an unbounded LockSupport.park() with nobody to unpark it on
                // the failure paths (run() only unparks after a successful bindAndAccept).
                // running=false does not wake a parked thread, so the thread — and the whole
                // shell-UID process, since it was not a daemon — stayed alive forever holding
                // the VirtualDisplay and both binds. Now it parks in bounded slices and
                // re-checks running; cleanup() additionally unparks explicitly.
                while (carVideoChannel == null && running) LockSupport.parkNanos(PARK_POLL_NANOS)
                val ch = carVideoChannel
                val enc = encoder
                if (running && ch != null && enc != null) glPipeline.pipelineLoop(enc, ch) { running }
            }
        } catch (e: Exception) { err("Pipeline: ${e.message}"); e.printStackTrace() }
        finally {
            // S-M8: EGL/GL teardown belongs to THIS thread — the EGL context was made
            // current here, and the encoder may still be inside dequeueOutputBuffer.
            // cleanup() on main/Lifecycle/TouchReader/watchdog can only request the exit
            // (running=false + unpark + bounded join); it never destroys EGL itself.
            releaseGlResources()
        }
        log("Pipeline exited")
    }

    /**
     * S-M8：销毁 GL/EGL 资源（幂等），且**只能**在 Pipeline 线程执行。
     *
     * 之前 `cleanup()` 从任意调用方线程（main / Lifecycle / TouchReader / Watchdog）
     * 直接 `glPipeline.cleanup()`：对一条 EGL context 由别的线程 makeCurrent 的线程
     * 做 eglDestroySurface/eglDestroyContext 属于跨线程 GL teardown，会和编码器的
     * native 调用打架。非属主线程调用这里只做"请求已发出"的记录，真正的销毁由
     * [runPipeline] 的 finally 完成；Pipeline 线程已退出时它当然也已经做完了。
     */
    private fun releaseGlResources() {
        if (!::glPipeline.isInitialized) return
        val owner = pipelineThread
        if (Thread.currentThread() !== owner) {
            if (owner != null && owner.isAlive) {
                err("GL teardown deferred to the Pipeline thread (still alive) — EGL may leak if it never exits")
            }
            return
        }
        try { glPipeline.cleanup() } catch (e: Exception) { err("GL cleanup: ${e.message}") }
    }

    /**
     * S-06/S-M8：请求 Pipeline 线程退出并等待它（有界）。
     *
     * `running=false` + 显式 unpark 是唤醒 parkNanos 循环的那一对：只有 run() 在成功
     * 路径 unpark 过一次，bindAndAccept 失败时没人 unpark。join 设上限是为了不在
     * cleanup 里永久阻塞 —— 线程卡在 native MediaCodec 调用里时，宁可泄漏 EGL 也不能
     * 把 panel 恢复一起拖死（那正是 watchdog 存在的原因）。
     */
    private fun signalPipelineStop() {
        running = false
        val t = pipelineThread ?: return
        LockSupport.unpark(t)
        try { t.join(PIPELINE_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (t.isAlive) err("Pipeline thread did not exit within ${PIPELINE_JOIN_TIMEOUT_MS}ms — GL/encoder teardown skipped")
    }

    // ── Lifecycle + Touch ──

    private fun startLifecycleReader(ch: SocketChannel) { Thread({ try { readLifecycleCommands(ch) } catch (e: Exception) { if (running) err("Lifecycle: ${e.message}") } }, "Lifecycle").apply { isDaemon = true }.start() }
    private fun readLifecycleCommands(ch: SocketChannel) {
        val r = NioReader(ch, 4096, frameIntervalNanos / 1_000_000)
        try {
            while (running) {
                if ((r.readByteBlocking().toInt() and 0xFF) == VdLifecycle.CMD_STOP) {
                    log("CMD_STOP")
                    break
                }
            }
        } catch (e: IOException) {
            if (running) err("Lifecycle: ${e.message}")
        } finally {
            running = false
            r.close()
            try { ch.close() } catch (_: Exception) {}
            cleanup()
        }
    }

    private fun startTouchReader(carInput: SocketChannel) { Thread({ try { readTouchAndCommands(carInput) } catch (e: Exception) { err("Touch: ${e.message}") } }, "TouchReader").apply { isDaemon = true }.start() }

    /**
     * Watchdog: when the lifecycle channel breaks, readLifecycleCommands sets
     * running=false and calls cleanup() in its finally block. But if the
     * pipeline thread is stuck in a native MediaCodec call (dequeueOutputBuffer
     * after stop()), the run() finally { cleanup() } never executes, and the
     * physical panel stays powered off → phone is unusable (black screen).
     *
     * This daemon polls running and cleanedUp. If running went false but
     * cleanup did not complete within GRACE_MS, it forces cleanup() directly
     * and then System.exit(1) so the JVM ShutdownHook also fires. The forced
     * path restores the panel via DisplayControl reflection — same mechanism
     * setPhysicalDisplayPower uses — and does NOT depend on Shizuku.
     */
    private fun startWatchdog() {
        Thread({
            while (running) {
                // 停止哨兵：部署序列 / PhoneDisplayRestorer 写文件请求优雅停止。
                // 不能用信号——ART 的 app_process 收 SIGTERM 直接终止、不跑 shutdown
                // hook，cleanup 会被整体跳过（2026-10-10 MI 9 实测，见 VdDeploy 类头）。
                // 这里置 running=false 后各等待点（accept 轮询 / park 切片 / bind 重试）
                // 会在 ≤100ms 内退出，run() 走到 finally，cleanup 完整执行。
                if (consumeStopRequest()) {
                    log("Stop request detected (${VdDeploy.STOP_REQUEST_PATH}) — graceful shutdown")
                    running = false
                    pipelineThread?.let { LockSupport.unpark(it) }
                    break
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
            val deadline = System.currentTimeMillis() + WATCHDOG_GRACE_MS
            while (System.currentTimeMillis() < deadline) {
                if (cleanedUp.get()) return@Thread
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
            if (!cleanedUp.get()) {
                err("Watchdog: cleanup did not complete within ${WATCHDOG_GRACE_MS}ms — forcing panel restore + exit")
                try { cleanup() } catch (e: Exception) { err("Watchdog cleanup error: ${e.message}") }
                // Give cleanup's setPhysicalDisplayPower(true) time to land before
                // halt() tears the process down (halt bypasses ShutdownHook).
                try { Thread.sleep(500) } catch (_: Exception) {}
                Runtime.getRuntime().halt(1)
            }
        }, "Watchdog").apply { isDaemon = true }.start()
    }

    private fun readTouchAndCommands(ch: SocketChannel) {
        val r = NioReader(ch, 65536, frameIntervalNanos/1_000_000)
        try {
            while (running) {
                val f = try { FrameCodec.readFrameBlocking(r) } catch (e: Exception) { null }
                if (f == null) break
                when (f.channel) {
                    Channel.INPUT -> touchInjector.handleTouchFrame(f)
                    Channel.CONTROL -> carCommands.dispatch(f)
                }
            }
        } finally {
            // Peer of readLifecycleCommands: when the car-input socket dies alone,
            // tear the whole pipeline down so the physical panel recovers
            // (setPhysicalDisplayPower(true) runs in cleanup()) instead of
            // leaving the encoder spinning with no consumer.
            running = false
            r.close()
            try { ch.close() } catch (_: Exception) {}
            cleanup()
        }
    }

    /**
     * Idempotent teardown. Order is deliberate:
     *
     *  1. Global window/rotation state is reset FIRST, while the shell is
     *  guaranteed alive and before anything that can block. These two are
     *  device-wide (not per-display), so a leaked value corrupts every later
     *  session, and they are cheap enough that doing them up front means even a
     *  process killed midway has already returned the device to normal.
     *  2. The foreground app is moved back to the physical display.
     *  3. The physical panel is re-powered and the IME restored — while the
     *  persistent shell is still usable (`setPhysicalDisplayPower` needs it).
     *  4. Threads, GL, encoder, and finally the VirtualDisplay are released.
     *
     * The previous order had `virtualDisplay.release()` dead last with nothing
     * after it to fail, so a mid-way exception or a SIGKILL from the phone side
     * left the panel off and the VD alive — one leaked display per reconnect.
     */
    private fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return
        running = false
        // 1. Global state first — these outlive this process if we die.
        try { shell.exec("cmd window reset-letterbox-style") } catch (_: Exception) {}
        if (displayId >= 0) {
            try { shell.exec("wm user-rotation -d $displayId free") } catch (_: Exception) {}
        }
        // 2. Resume foreground app from VD to phone display before tearing down VD
        carCommands.moveTopApp(displayId, 0)
        // 3. Restore screen BEFORE killing shell (order matters: shell.exec needs the shell alive)
        displayController.setPhysicalDisplayPower(true)
        try { shell.exec("input keyevent 224") } catch (_: Exception) {}
        displayController.restoreIme()
        // 4. Stop the LifeWriter thread before killing the shell — it may be mid-write.
        lifecycle.stop()
        // 4b. 屏障（2026-10-10，MI 9 实测）：上面的恢复命令是 fire-and-forget 写进
        // 常驻 sh 的 stdin，而 close() 的 destroy() 会立刻 SIGTERM 掉它 —— 最后
        // 几条命令还没被 sh 读走就随进程死了。实测丢的正是
        // `settings put system screen_off_timeout 60000`：日志完整显示"恢复成功 +
        // Cleanup complete"，但会话结束后值仍停在 2147483647 哨兵。echo 一个唯一
        // 标记并等排水线程回显（stdin/stdout 双 FIFO）⇒ 此前命令都已执行完。
        shell.awaitIdle()
        // Now kill the shell
        shell.close()
        // S-M8/S-06: ask the pipeline thread to exit (running=false + unpark) and wait for it
        // (bounded) BEFORE touching the encoder — the render loop may still be inside a
        // native dequeueOutputBuffer/eglSwapBuffers call, which is what used to race
        // cleanup() here.
        signalPipelineStop()
        encoder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        // S-M8: the encoder's input Surface used to be leaked — releasing the codec does not
        // release the Surface we handed it, so every session burned one for nothing.
        encoderSurface?.let { try { it.release() } catch (_: Exception) {} }
        // S-M8: EGL/GL is torn down by the pipeline thread itself (releaseGlResources);
        // signalPipelineStop above is how this thread asks for it.
        releaseGlResources()
        virtualDisplay?.let { try { it.release() } catch (_: Exception) {} }
        log("Cleanup complete")
    }
}

/**
 * 编码器候选排序（纯函数，单测锁定契约 —— 见 `EncoderCandidateOrderTest`）。
 *
 * 排序键（稳定排序，同键保留框架给出的原顺序）：
 *  1. 非纯软实现优先（`isSoftwareOnly=false`，硬编性能）；
 *  2. 同组内 Codec2（`c2.` 前缀）优先于遗留 OMX（`OMX.` 前缀）—— 新设备上的
 *     OMX 封装常是陈旧/残缺实现。2026-10-10 MI 9 实机：`OMX.qcom.video.encoder.avc`
 *     在 configure 即失败（不支持 COLOR_FormatSurface），`c2.qti.avc.encoder` 正常。
 *
 * 排序只决定"先试谁"，正确性由 [PipelineServer.setupEncoder] 的逐个真尝试兜底：
 * 靠前的候选失败会自动落到下一个，直到 `c2.android`（软编）之类的最后防线。
 */
internal fun orderEncoderCandidates(candidates: List<Pair<String, Boolean>>): List<String> =
    candidates
        .sortedWith(compareBy({ it.second }, { if (it.first.startsWith("c2.")) 0 else 1 }))
        .map { it.first }

/**
 * 消费停止哨兵（协议见 [VdDeploy.gracefulStopCommand]）：文件存在则**删除并返回
 * true**——删除是给外部的"已受理"信号（可选观察），删不掉也不影响停止（残留由
 * 下一次启动的 `run()` 开头兜底清理）。不存在则返回 false。
 *
 * Top-level 且路径可注入：vd-server 的单测在纯 JVM 上跑，用临时目录验证
 * "不存在→false / 存在→true 且被删除 / 二次调用→false"的契约；watchdog 的
 * 线程集成不在单测范围内。
 */
internal fun consumeStopRequest(path: String = VdDeploy.STOP_REQUEST_PATH): Boolean {
    val f = File(path)
    if (!f.exists()) return false
    runCatching { f.delete() }
    return true
}
