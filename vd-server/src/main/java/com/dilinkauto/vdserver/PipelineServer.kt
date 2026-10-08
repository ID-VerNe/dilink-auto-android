package com.dilinkauto.vdserver

import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.dilinkauto.protocol.*
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
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
 */
class PipelineServer(
    private val displayWidth: Int, private val displayHeight: Int, private val dpi: Int,
    private val phoneHost: String, private val encodeWidth: Int, private val encodeHeight: Int,
    private val fps: Int, private val bitrate: Int = BITRATE
) {
    private val frameIntervalNanos = 1_000_000_000L / fps
    @Volatile private var running = true
    private val cleanedUp = AtomicBoolean(false)
    private var displayId = -1
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null

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

        @JvmStatic fun main(args: Array<String>) {
            AndroidPlatformHooks.install()
            val w = args.getOrNull(0)?.toInt() ?: 1408; val h = args.getOrNull(1)?.toInt() ?: 792
            val d = args.getOrNull(2)?.toInt() ?: 120; val ph = args.getOrNull(3) ?: "127.0.0.1"
            val ew = args.getOrNull(4)?.toInt() ?: w; val eh = args.getOrNull(5)?.toInt() ?: h
            val f = args.getOrNull(6)?.toInt() ?: VideoConfig.TARGET_FPS
            val br = args.getOrNull(7)?.toInt() ?: BITRATE
            log("Starting: VD=${w}x${h} @${d}dpi, encode=${ew}x${eh}, phoneHost=$ph, fps=$f, bitrate=${br/1_000_000}M")
            PipelineServer(w, h, d, ph, ew, eh, f, br).run()
        }
        private fun log(msg: String) = PipeLog.log(msg)
        private fun err(msg: String) = PipeLog.err(msg)
    }

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread({ cleanup() }, "ShutdownHook"))
        startWatchdog()
        try {
            touchInjector.initInputManager()
            shell.start()
            try { setupEncoder() } catch (e: Exception) { err("Fatal: encoder: ${e.message}"); return }
            val enc = encoder ?: return
            encoderSurface = enc.createInputSurface()
            enc.start()

            glPipeline = GlPipeline(
                displayWidth, displayHeight, encodeWidth, encodeHeight, fps,
                frameIntervalNanos, bitrate
            ).also { it.encoderSurface = encoderSurface }

            // Start pipeline thread — it initializes EGL/GL and signals when VD input surface is ready
            val pipelineThread = Thread({ runPipeline() }, "Pipeline").apply { start() }
            try { inputSurfaceReady.await() } catch (_: InterruptedException) { return }
            if (!createVirtualDisplay()) { running = false; err("Fatal: failed to create VD"); return }
            val conns = bindAndAccept() ?: run { running = false; return }
            startLifecycleReader(conns.phoneChannel)
            startTouchReader(conns.carInput)
            lifecycle.start()
            // Signal pipeline to begin rendering with the car video channel
            carVideoChannel = conns.carVideo
            LockSupport.unpark(pipelineThread)
            try { pipelineThread.join() } catch (_: InterruptedException) {}
        } finally {
            cleanup()
        }
    }

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
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        log("Encoder: ${encodeWidth}x${encodeHeight} CBR@${bitrate/1_000_000}Mbps Main ${fps}fps")
    }

    // ── VD Creation ──

    private fun createVirtualDisplay(): Boolean {
        val vdSurf = vdInputSurface ?: return false
        // Snapshot the system state we are about to change — BEFORE changing it.
        // This used to run after VirtualDisplayCreator.create(), which itself
        // wrote screen_off_timeout=2147483647 / lift_wakeup=0 / proximity=0, so
        // the "original" snapshot captured our own writes and restoreIme() could
        // never put the user's real values back.
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
        val phoneChannel = connectToPhoneHost() ?: run { try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}; return null }
        try { sendDisplayReady(phoneChannel); log("Display ready sent") } catch (e: Exception) { err("Display ready: ${e.message}"); try { phoneChannel.close() } catch (_: Exception) {}; try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}; return null }
        lifecycle.channel = phoneChannel
        shell.exec("input keyevent 224"); log("Waking up device to ensure VD activities resume")
        shell.exec("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.HOME"); log("Home launched")
        carCommands.moveTopApp(0, displayId)
        // 校验 DTA 是否挂在 display 层树内（Flyme 偶发 reparent 事务丢失 → 0 层黑屏），必要时旋转往返修复
        vdCreator.ensureDtaAttached()
        // Physical panel power-off happens *after* the car connects — see below.
        val carVideo = acceptCarChannel(videoServer, 30000) ?: run { err("Car video timeout"); try { phoneChannel.close() } catch (_: Exception) {}; return null }
        val carInput = acceptCarChannel(inputServer, 30000) ?: run { err("Car input timeout"); try { phoneChannel.close() } catch (_: Exception) {}; try { carVideo.close() } catch (_: Exception) {}; return null }
        try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}
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
        while (running && System.currentTimeMillis() < deadline) { val a = server.accept(); if (a != null) { a.configureBlocking(false); val s = a.socket(); s.sendBufferSize = SOCKET_BUF_BYTES; s.receiveBufferSize = SOCKET_BUF_BYTES; s.tcpNoDelay = true; return a }; Thread.sleep(50) }
        return null
    }

    // ── Pipeline thread ──

    private fun runPipeline() {
        try {
            // Encoder + GL pipeline thread; mark urgent so background threads
            // (LifeWriter, watchdog) don't preempt the encode path on A53.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            glPipeline.initEglAndSurfaceTexture()
            vdInputSurface = glPipeline.vdSurface()
            inputSurfaceReady.countDown()
            // Wait for main thread to create VD, accept connections, and set carVideoChannel
            while (carVideoChannel == null && running) LockSupport.park()
            if (!running) return
            val enc = encoder ?: return
            glPipeline.pipelineLoop(enc, carVideoChannel!!) { running }
        } catch (e: Exception) { err("Pipeline: ${e.message}"); e.printStackTrace() }
        log("Pipeline exited")
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
        // Now kill the shell
        shell.close()
        // GL/EGL cleanup
        if (::glPipeline.isInitialized) glPipeline.cleanup()
        encoder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        virtualDisplay?.let { try { it.release() } catch (_: Exception) {} }
        log("Cleanup complete")
    }
}
