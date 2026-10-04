package com.dilinkauto.vdserver

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import com.dilinkauto.protocol.*
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Method
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
 *
 * This class owns: process lifecycle, encoder setup, VirtualDisplay creation,
 * socket bind/accept, the lifecycle/touch reader threads, the LifeWriter,
 * the watchdog, and cleanup ordering. It runs as shell via `app_process`.
 */
class PipelineServer(
    private val displayWidth: Int, private val displayHeight: Int, private val dpi: Int,
    private val phoneHost: String, private val encodeWidth: Int, private val encodeHeight: Int,
    private val fps: Int
) {
    private val frameIntervalNanos = 1_000_000_000L / fps
    @Volatile private var running = true
    private val cleanedUp = AtomicBoolean(false)
    private var displayId = -1
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null

    private var persistentShell: Process? = null
    private var shellInput: OutputStream? = null
    private var lastPowerOffTime = 0L

    // SurfaceTexture + VD surface (created on pipeline thread, used by VD)
    private var vdInputSurface: android.view.Surface? = null
    private var encoderSurface: android.view.Surface? = null
    private var lifecycleChannel: SocketChannel? = null

    private val touchInjector = TouchInjector(
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        displayIdProvider = { displayId },
        shellProvider = { shellInput },
        logErr = { err(it) }
    )
    private val displayController = DisplayPowerController(
        shellProvider = { shellInput },
        execShellOutput = { execShellOutput(it) },
        logErr = { err(it) }
    )
    private val vdCreator = VirtualDisplayCreator(
        displayWidth = displayWidth,
        displayHeight = displayHeight,
        dpi = dpi,
        execShell = { execShell(it) },
        execShellOutput = { execShellOutput(it) },
        log = { log(it) },
        err = { err(it) }
    )
    private lateinit var glPipeline: GlPipeline

    // Synchronization between main thread and pipeline thread
    private val inputSurfaceReady = CountDownLatch(1)
    @Volatile private var carVideoChannel: SocketChannel? = null

    companion object {
        private const val MSG_DISPLAY_READY: Byte = 0x10
        private const val MSG_STACK_EMPTY: Byte = 0x11
        private const val CMD_STOP = 0xFF
        private const val BITRATE = 4_000_000
        private const val I_FRAME_INTERVAL = 1
        private const val WATCHDOG_GRACE_MS = 3000L
        private const val SOCKET_BUF_BYTES = 262144 // 256KB — request send/receive buffer

        @JvmStatic fun main(args: Array<String>) {
            val w = args.getOrNull(0)?.toInt() ?: 1408; val h = args.getOrNull(1)?.toInt() ?: 792
            val d = args.getOrNull(2)?.toInt() ?: 120; val ph = args.getOrNull(3) ?: "127.0.0.1"
            val ew = args.getOrNull(4)?.toInt() ?: w; val eh = args.getOrNull(5)?.toInt() ?: h
            val f = args.getOrNull(6)?.toInt() ?: 30
            log("Starting: VD=${w}x${h} @${d}dpi, encode=${ew}x${eh}, phoneHost=$ph, fps=$f")
            PipelineServer(w, h, d, ph, ew, eh, f).run()
        }
        private fun log(msg: String) = PipeLog.log(msg)
        private fun err(msg: String) = PipeLog.err(msg)
    }

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread({ cleanup() }, "ShutdownHook"))
        startWatchdog()
        try {
            touchInjector.initInputManager()
            initPersistentShell()
            try { setupEncoder() } catch (e: Exception) { err("Fatal: encoder: ${e.message}"); return }
            val enc = encoder ?: return
            encoderSurface = enc.createInputSurface()
            enc.start()

            glPipeline = GlPipeline(
                displayWidth, displayHeight, encodeWidth, encodeHeight, fps,
                frameIntervalNanos, BITRATE
            ).also { it.encoderSurface = encoderSurface }

            // Start pipeline thread — it initializes EGL/GL and signals when VD input surface is ready
            val pipelineThread = Thread({ runPipeline() }, "Pipeline").apply { start() }
            try { inputSurfaceReady.await() } catch (_: InterruptedException) { return }
            if (!createVirtualDisplay()) { running = false; err("Fatal: failed to create VD"); return }
            val conns = bindAndAccept() ?: run { running = false; return }
            startLifecycleReader(conns.phoneChannel)
            startTouchReader(conns.carInput)
            lifeWriterThread.start()
            // Signal pipeline to begin rendering with the car video channel
            carVideoChannel = conns.carVideo
            LockSupport.unpark(pipelineThread)
            try { pipelineThread.join() } catch (_: InterruptedException) {}
        } finally {
            cleanup()
        }
    }

    private fun initPersistentShell() {
        try { persistentShell = Runtime.getRuntime().exec(arrayOf("sh")); shellInput = persistentShell!!.outputStream } catch (e: Exception) { err("Shell: ${e.message}") }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encodeWidth, encodeHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
        format.setInteger(MediaFormat.KEY_LATENCY, 0); format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0); format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        format.setLong("repeat-previous-frame-after", 500_000L)
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        log("Encoder: ${encodeWidth}x${encodeHeight} CBR@${BITRATE/1_000_000}Mbps Main ${fps}fps")
    }

    // ── VD Creation ──

    private fun createVirtualDisplay(): Boolean {
        val vdSurf = vdInputSurface ?: return false
        virtualDisplay = vdCreator.create(vdSurf)
        if (virtualDisplay == null) return false
        displayId = vdCreator.displayId
        displayController.saveCurrentIme()
        try { displayController.setDisplayImePolicy(displayId) } catch (_: Exception) {}
        return true
    }

    // ── Connection ──

    data class ConnectionSet(val carVideo: SocketChannel, val carInput: SocketChannel, val phoneChannel: SocketChannel)

    private fun bindAndAccept(): ConnectionSet? {
        val videoServer: ServerSocketChannel; val inputServer: ServerSocketChannel
        try {
            videoServer = ServerSocketChannel.open(); videoServer.configureBlocking(false); videoServer.socket().reuseAddress = true
            videoServer.socket().bind(InetSocketAddress("0.0.0.0", Discovery.VIDEO_PORT)); log("Video on :${Discovery.VIDEO_PORT}")
            inputServer = ServerSocketChannel.open(); inputServer.configureBlocking(false); inputServer.socket().reuseAddress = true
            inputServer.socket().bind(InetSocketAddress("0.0.0.0", Discovery.INPUT_PORT)); log("Input on :${Discovery.INPUT_PORT}")
        } catch (e: Exception) { err("Bind: ${e.message}"); return null }
        val phoneChannel = connectToPhoneHost() ?: run { try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}; return null }
        try { sendDisplayReady(phoneChannel); log("Display ready sent") } catch (e: Exception) { err("Display ready: ${e.message}"); try { phoneChannel.close() } catch (_: Exception) {}; try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}; return null }
        lifecycleChannel = phoneChannel
        execShell("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.HOME"); log("Home launched")
        moveTopApp(0, displayId)
        displayController.setPhysicalDisplayPower(false); lastPowerOffTime = System.currentTimeMillis()
        val carVideo = acceptCarChannel(videoServer, "video", 30000) ?: run { err("Car video timeout"); try { phoneChannel.close() } catch (_: Exception) {}; return null }
        val carInput = acceptCarChannel(inputServer, "input", 30000) ?: run { err("Car input timeout"); try { phoneChannel.close() } catch (_: Exception) {}; try { carVideo.close() } catch (_: Exception) {}; return null }
        try { videoServer.close() } catch (_: Exception) {}; try { inputServer.close() } catch (_: Exception) {}
        log("Car connected: video=${carVideo.remoteAddress} input=${carInput.remoteAddress}")
        return ConnectionSet(carVideo, carInput, phoneChannel)
    }

    private fun connectToPhoneHost(): SocketChannel? {
        val addr = InetSocketAddress(phoneHost, Discovery.LIFECYCLE_PORT)
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
        val buf = ByteBuffer.allocate(6); buf.put(MSG_DISPLAY_READY); buf.putInt(displayId); buf.put(if (hasInjection) 1 else 0); buf.flip()
        ch.configureBlocking(true); while (buf.hasRemaining()) ch.write(buf); ch.configureBlocking(false)
    }

    private fun acceptCarChannel(server: ServerSocketChannel, name: String, timeoutMs: Int): SocketChannel? {
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
                if ((r.readByteBlocking().toInt() and 0xFF) == CMD_STOP) {
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
                    Channel.CONTROL -> handleCarCommand(f)
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

    private fun handleCarCommand(f: FrameCodec.Frame) {
        when (f.messageType) {
            ControlMsg.LAUNCH_APP -> launchApp(LaunchAppMessage.decode(f.payload).packageName)
            ControlMsg.GO_BACK -> { execShell("input -d $displayId keyevent 4"); checkStackEmpty() }
            ControlMsg.GO_HOME -> { execShell("input -d $displayId keyevent 3"); checkStackEmpty() }
            ControlMsg.GO_RECENT -> { execShell("input -d $displayId keyevent 187"); checkStackEmpty() }
            ControlMsg.APP_UNINSTALL -> execShell("pm uninstall ${String(f.payload, Charsets.UTF_8)}")
            ControlMsg.APP_INFO -> { val pkg = String(f.payload, Charsets.UTF_8); val s = execShellOutput("cmd package resolve-activity --brief -a android.settings.APPLICATION_DETAILS_SETTINGS com.android.settings")?.trim(); if (!s.isNullOrEmpty()) execShell("am start --display $displayId -n $s -d \"package:$pkg\"") else execShell("am start --display $displayId -a android.settings.APPLICATION_DETAILS_SETTINGS -d \"package:$pkg\"") }
        }
    }
    private fun launchApp(pkg: String) { try { val c = execShellOutput("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg 2>/dev/null | tail -1")?.trim(); if (!c.isNullOrEmpty()) execShell("am start --display $displayId -n $c") else execShell("am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg") } catch (e: Exception) { err("launch: ${e.message}") } }

    private fun checkStackEmpty() { Thread({ try { Thread.sleep(300); val d = execShellOutput("dumpsys activity activities 2>/dev/null") ?: ""; val m = "Display #$displayId "; val s = d.indexOf(m); if (s < 0) { enqueueResponse(MSG_STACK_EMPTY, ByteArray(0)) } else { val nd = d.indexOf("Display #", s+m.length); val sec = if (nd >= 0) d.substring(s, nd) else d.substring(s); if (sec.lines().none { it.contains("Task{") }) enqueueResponse(MSG_STACK_EMPTY, ByteArray(0)) } } catch (_: Exception) {} }, "StackCheck").start() }

    private val lifecycleWriteQueue = java.util.concurrent.ArrayBlockingQueue<ByteBuffer>(16)
    private val lifeWriterThread = Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        while (running) {
            val buf = try { lifecycleWriteQueue.take() } catch (_: InterruptedException) { break }
            val ch = lifecycleChannel
            if (ch == null || !ch.isOpen) continue
            try {
                synchronized(ch) {
                    while (buf.hasRemaining()) {
                        val n = ch.write(buf)
                        if (n == 0) Thread.sleep(1)
                    }
                }
            } catch (_: Exception) {
                // Swallowed: lifecycle responses are advisory (stack-empty).
                // Dropping one on a transient I/O error is preferable to crashing the
                // pipeline, which a configureBlocking(true) toggle would do (see below).
            }
        }
    }, "LifeWriter").apply { isDaemon = true }

    /** Enqueue a response on the lifecycle channel through the dedicated LifeWriter
     *  thread. The lifecycle Channel is registered for OP_READ by NioReader; toggling
     *  its blocking mode to write synchronously throws IllegalBlockingModeException,
     *  which the old try/catch silently swallowed — so every MSG_STACK_EMPTY was
     *  dropped. The single-writer thread performs a non-blocking spin write without
     *  touching blocking mode. */
    private fun enqueueResponse(msgType: Byte, payload: ByteArray) {
        try {
            val len = if (payload.isEmpty()) 1 else 5 + payload.size
            val buf = ByteBuffer.allocate(len)
            buf.put(msgType)
            if (payload.isNotEmpty()) { buf.putInt(payload.size); buf.put(payload) }
            buf.flip()
            lifecycleWriteQueue.offer(buf)
        } catch (_: Exception) {}
    }

    // ── Helpers ──

    private fun execShell(cmd: String) = ShellExec.execShell(shellInput, cmd)
    private fun execShellOutput(cmd: String): String? = ShellExec.execShellOutput(cmd)

    private fun moveTopApp(fromDisplay: Int, toDisplay: Int) {
        if (fromDisplay < 0 || toDisplay < 0) return
        // `am display move-stack` was added in API 29 (Android 10). On API 26-28 the
        // subcommand is absent — am prints a usage error and exits non-zero. Skip the
        // whole feature on older levels so the foreground app is left in place rather
        // than a silent shell failure masking as success.
        if (android.os.Build.VERSION.SDK_INT < 29) {
            log("moveTopApp: skipping, am display move-stack requires API 29 (current ${android.os.Build.VERSION.SDK_INT})")
            return
        }
        try {
            val d = execShellOutput("dumpsys activity activities 2>/dev/null") ?: return
            val m = "Display #$fromDisplay "
            val s = d.indexOf(m)
            if (s < 0) return
            val nd = d.indexOf("Display #", s + m.length)
            val sec = if (nd >= 0) d.substring(s, nd) else d.substring(s)
            val match = Regex("ActivityRecord\\{[^ ]+ [^ ]+ ([^/ ]+/[^ } ]+) t(\\d+)\\}").find(sec)
            val topComponent = match?.groupValues?.get(1)
            val taskId = match?.groupValues?.get(2)
            if (topComponent != null && taskId != null && !topComponent.contains("launcher", true) && !topComponent.contains("systemui", true)) {
                log("Moving app $topComponent (Task $taskId) from display $fromDisplay to $toDisplay")
                execShell("am display move-stack $taskId $toDisplay")
            }
        } catch (e: Exception) {
            err("Failed to move app: ${e.message}")
        }
    }

    private fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return
        running = false
        // Resume foreground app from VD to phone display before tearing down VD
        moveTopApp(displayId, 0)
        // Restore screen BEFORE killing shell (order matters: execShell needs shellInput alive)
        displayController.setPhysicalDisplayPower(true)
        try { execShell("input keyevent 224") } catch (_: Exception) {}
        displayController.restoreIme()
        try { execShell("cmd window reset-letterbox-style") } catch (_: Exception) {}
        // Stop the LifeWriter thread before killing the shell — it may be mid-write.
        lifeWriterThread.interrupt()
        // Now kill the shell
        persistentShell?.let { try { shellInput?.close() } catch (_: Exception) {}; it.destroy() }
        // GL/EGL cleanup
        if (::glPipeline.isInitialized) glPipeline.cleanup()
        encoder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        virtualDisplay?.let { try { it.release() } catch (_: Exception) {} }
        log("Cleanup complete")
    }
}
