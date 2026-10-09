package com.dilinkauto.server.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock

import android.util.Log
import android.view.Surface
import com.dilinkauto.protocol.BlackScreenDetector
import com.dilinkauto.protocol.H264NalParser
import com.dilinkauto.protocol.VideoConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes H.264 video frames received from the phone and renders them to a Surface.
 *
 * Frames are queued from the network thread and fed to MediaCodec on a dedicated thread.
 * CONFIG frames (SPS/PPS) are cached so the decoder can be started/restarted at any time.
 */
class VideoDecoder {

    /** Log callback — set by CarConnectionService to route logs to the phone via protocol. */
    var logSink: ((String) -> Unit)? = null

    /**
     * When true, the per-30-frame stat log includes cumulative decode time and
     * queue depth. Default off — the volume is noise in release. Toggled by
     * CarConnectionService when dev diagnostics are enabled (Phase L1 / perf 9.3).
     */
    @Volatile
    var debugFrameStats = false

    // logSink routes through carLogSend, which already calls Log.i (single logcat path).
    // Avoid double-logging here — the carLogSend side is the one source of truth for logcat.
    private fun log(msg: String) {
        logSink?.invoke("[VideoDecoder] $msg") ?: Log.i(TAG, msg)
    }

    private fun logW(msg: String) {
        logSink?.invoke("[VideoDecoder][W] $msg") ?: Log.w(TAG, msg)
    }

    private fun logE(msg: String) {
        logSink?.invoke("[VideoDecoder][E] $msg") ?: Log.e(TAG, msg)
    }

    private var codec: MediaCodec? = null
    private var feedThread: Thread? = null
    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()
    private val frameQueue = ArrayBlockingQueue<FrameData>(4) // small buffer, drop on overflow

    /**
     * Guards start()/stop() as whole critical sections (audit S-07/S-08).
     *
     * MediaCodec lifecycle is cross-thread: MirrorScreen's surfaceCreated
     * (Main, now via the service's decoder executor) creates the codec while
     * handleDisconnect/shutdown tear it down from other threads. Without this
     * lock, `running.getAndSet(true)` before codec creation lets a concurrent
     * stop() release a codec nobody is feeding, or flip `running` false so the
     * fresh feed thread exits immediately — the orphan-codec / black-screen
     * pair. start()/stop() bodies run entirely inside this lock.
     */
    private val lifecycleLock = Any()

    // Surface validity flag. SurfaceView (used since the Adreno 505 TextureView
    // composite cost was too high) destroys its surface when the view goes INVISIBLE;
    // TextureView kept it alive. Navigation HOME<->APP toggles visibility, so
    // surfaceDestroyed fires on each switch. The decoder keeps running across
    // navigation (handleDisconnect/shutdown owns the final stop); we gate
    // releaseOutputBuffer's render flag on this so we don't render to a destroyed
    // surface during the brief window before surfaceCreated re-attaches.
    @Volatile
    private var outputSurfaceValid = false

    // CONFIG data is cached separately so it's never lost, even if it arrives
    // before start() is called or while the queue is full.
    @Volatile
    private var configData: ByteArray? = null
    // Most recent IDR keyframe cached while the decoder is stopped. On rotation the
    // decoder stops before the fresh IDR arrives; without this cache the new codec
    // instance would have CONFIG but no reference frame, so P-frames render nothing
    // until the next live IDR (1-2s of black). start() feeds this right after CONFIG.
    @Volatile
    private var cachedKeyFrame: ByteArray? = null
    @Volatile
    private var seekingKeyFrame = false

    private var frameCount = 0L
    private var receiveCount = 0L
    private var renderCount = 0L
    private var dropCount = 0L
    private var inputFailCount = 0L
    private var keyFramesReceived = 0L
    private var keyFramesFed = 0L
    private var keyFramesDropped = 0L

    // 黑屏检测策略在 protocol-core（车机端 VideoDecoder 与桌面端 VideoDecodePipeline
    // 共用一套判据，可脱离 MediaCodec 单测）。车机端沿用 elapsedRealtime（含休眠），
    // 桌面端用默认的 System.nanoTime。
    private val blackScreen = BlackScreenDetector(
        clock = { SystemClock.elapsedRealtime() },
    ).apply {
        sustainMs = BLACK_SCREEN_SUSTAIN_MS
    }

    /**
     * Called once per session when the stream is *persistently* black — not on
     * the third tiny keyframe, but only after a sustained window. See
     * [BlackScreenDetector] for the rationale.
     */
    @Volatile
    var onSustainedBlackScreen: (() -> Unit)? = null

    /** Reset the black-screen detector so a fresh session starts clean. */
    fun resetBlackScreenState() = blackScreen.reset()

    // Accumulated decode time for the current 30-frame window (Phase L1 / perf 9.3).
    // Reset at each per-30-frame log so the reported value is per-window, not lifetime.
    private var windowDecodeNanos = 0L

    /** True if [data] contains an H.264 IDR frame (NAL type 5). Delegates to
     *  [H264NalParser]（已迁至 :protocol-core，桌面端共用同一判定）；薄包装让调用点保持本地命名。 */
    private fun isKeyFrame(data: ByteArray): Boolean = H264NalParser.isKeyFrame(data)

    data class FrameData(val isConfig: Boolean, val isKeyFrame: Boolean, val data: ByteArray)

    /**
     * Starts the decoder, rendering to the provided Surface.
     *
     * Never call this from the main thread in production — the service routes
     * it through its decoder executor (audit S-M3). The whole body is
     * serialized against [stop] by [lifecycleLock] (audit S-07/S-08).
     */
    fun start(surface: Surface, width: Int, height: Int, fps: Int = VideoConfig.TARGET_FPS) {
        synchronized(lifecycleLock) {
            if (running.getAndSet(true)) {
                logW("start() called but already running")
                return
            }

            // fps can be 0 or absurd from a persisted pref (audit S-L2): the
            // frameQueue.poll timeout below divides by it, and MediaFormat's
            // operating rate must be a real value. Coerce into the protocol's
            // declared range once, here, so every downstream use is safe.
            val effectiveFps = coerceFps(fps)
            if (effectiveFps != fps) {
                logW("fps $fps out of range — coerced to $effectiveFps")
            }

            log("Starting decoder: ${width}x${height} @${effectiveFps}fps, cached config=${configData != null}, queued=${frameQueue.size}")

            // Re-arm the black-screen detector: a fresh stream gets a fresh chance, and
            // the "escalate once" latch must not survive into the new session.
            resetBlackScreenState()

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, effectiveFps)
            }

            // Re-check `running` AFTER creating the codec (audit S-08): a
            // concurrent stop() that interleaved before the lock was taken
            // (or a cancelled executor task) must not leave a live codec nobody
            // feeds. Release it right here instead.
            val newCodec: MediaCodec = try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(format, surface, null, 0)
                    start()
                }
            } catch (e: Exception) {
                logE("Failed to create/start MediaCodec: ${e.message}")
                running.set(false)
                return
            }
            codec = newCodec

            if (!running.get()) {
                // Lost a race with stop(): tear down what we just built.
                logW("start(): stop() raced codec creation — releasing the fresh codec")
                try { newCodec.stop() } catch (_: Exception) {}
                try { newCodec.release() } catch (_: Exception) {}
                codec = null
                return
            }

            outputSurfaceValid = true
            log("MediaCodec created: name=${newCodec.name} dims=${width}x${height} operatingRate=$effectiveFps")

            frameCount = 0
            renderCount = 0
            dropCount = 0
            inputFailCount = 0
            var configFed = false

            feedThread = Thread({
                // 8x A53 has no big cores; tell the scheduler this is latency-critical
                // so it doesn't get starved by background work (icon decode, log flush).
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                // Capture the codec reference the thread owns (audit S-07): if
                // stop() cannot join us, the thread's own finally releases
                // *this* instance — never the field, which may already point at
                // a newer one.
                val decoder: MediaCodec = newCodec
                try {
                    log("Feed thread started")

                    // If we have cached config, feed it first before any frames.
                    // Without SPS/PPS, the decoder silently fails on non-config frames.
                    configData?.let { config ->
                        log("Feeding cached CONFIG (${config.size} bytes)")
                        feedBuffer(decoder, config, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        configFed = true
                    }

                    // Feed the cached IDR (captured while the decoder was stopped) so the
                    // codec has a reference frame immediately and P-frames render without
                    // waiting for the next live IDR. seekingKeyFrame stays false — this
                    // keyframe IS the reference, not a post-flush resync.
                    cachedKeyFrame?.let { key ->
                        log("Feeding cached KEYFRAME for cold-start reference (${key.size} bytes)")
                        feedBuffer(decoder, key, 0)
                        cachedKeyFrame = null
                    }

                    // With 4-frame queue, catchup is unnecessary — frames arrive on time or get dropped.
                    var skipCount = 0L

                    while (running.get()) {
                        // Drain output first to free decoder buffers before trying to feed
                        drainOutput(decoder)

                        val frame = try {
                            frameQueue.poll(1000L / effectiveFps, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            continue
                        } ?: continue

                        if (frame.isConfig) {
                            configData = frame.data
                            log("Feeding CONFIG (${frame.data.size} bytes)")
                            feedBuffer(decoder, frame.data, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                            configFed = true
                        } else if (!configFed) {
                            if (frameCount == 0L) log("Waiting for CONFIG before feeding video frames")
                            continue
                        } else {
                            val isKey = frame.isKeyFrame
                            // After a flush, MediaCodec has no reference frame — feeding P-frames
                            // produces no output and clogs input buffers until the next IDR. Drain
                            // and discard everything until a keyframe arrives. onFrameReceived gives
                            // keyframes queue priority, so the next IDR reaches the head quickly.
                            if (seekingKeyFrame && !isKey) {
                                skipCount++
                                if (skipCount <= 5 || skipCount % 30 == 0L) {
                                    logW("Post-flush: skipping P-frame #$skipCount until next IDR")
                                }
                                continue
                            }
                            if (seekingKeyFrame && isKey) {
                                seekingKeyFrame = false
                                log("Post-flush: resynced at IDR keyframe after skipping $skipCount P-frames")
                                skipCount = 0L
                            }
                            if (isKey) {
                                keyFramesFed++
                                log("Feeding KEYFRAME #$keyFramesFed size=${frame.data.size} (fed=$frameCount rendered=$renderCount)")
                            }
                            val decodeStart = if (debugFrameStats) System.nanoTime() else 0L
                            feedBuffer(decoder, frame.data, 0)
                            if (debugFrameStats) windowDecodeNanos += System.nanoTime() - decodeStart
                            frameCount++
                            if (frameCount % 30 == 0L) {
                                if (debugFrameStats) {
                                    val decodeMs = windowDecodeNanos / 1_000_000.0
                                    log("Fed $frameCount rendered=$renderCount drops=$dropCount inputFails=$inputFailCount keys_recv=$keyFramesReceived keys_fed=$keyFramesFed keys_drop=$keyFramesDropped queue=${frameQueue.size} skips=$skipCount decodeMs=${"%.1f".format(decodeMs)}")
                                    windowDecodeNanos = 0L
                                } else {
                                    log("Fed $frameCount rendered=$renderCount drops=$dropCount inputFails=$inputFailCount keys_recv=$keyFramesReceived keys_fed=$keyFramesFed keys_drop=$keyFramesDropped queue=${frameQueue.size} skips=$skipCount")
                                }
                            }
                        }

                        // Drain all available output
                        drainOutput(decoder)
                    }
                    log("Feed thread exiting: fed=$frameCount rendered=$renderCount drops=$dropCount")
                } finally {
                    // The thread owns the codec it captured (audit S-07): release
                    // it here whether we exited normally or threw. This is the
                    // only release path that is guaranteed to run *after* this
                    // thread has left every native MediaCodec call.
                    try { decoder.stop() } catch (_: Exception) {}
                    try { decoder.release() } catch (_: Exception) {}
                }
            }, "VideoDecoderFeed").apply { start() }
        }
    }

    private var consecutiveDrops = 0
    private val drainBufferInfo = MediaCodec.BufferInfo()

    private fun feedBuffer(decoder: MediaCodec, data: ByteArray, flags: Int) {
        if (!running.get()) return
        try {
            val inputIndex = decoder.dequeueInputBuffer(2000) // 2ms — drain-first, don't block on input
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                inputBuffer.clear()
                inputBuffer.put(data)
                val pts = System.nanoTime() / 1000 // wall-clock microseconds
                decoder.queueInputBuffer(inputIndex, 0, data.size, pts, flags)
                consecutiveDrops = 0
            } else {
                consecutiveDrops++
                inputFailCount++
                if (consecutiveDrops <= 3 || consecutiveDrops % 10 == 0) {
                    logW("dequeueInputBuffer failed: consecutiveDrops=$consecutiveDrops, size=${data.size}, flags=$flags")
                }
                if (consecutiveDrops > 10) {
                    logW("Decoder stuck ($consecutiveDrops drops), flushing")
                    decoder.flush()
                    consecutiveDrops = 0
                    // After flush the decoder has no reference frame; set the
                    // seek flag so the feed loop drains P-frames until the next
                    // IDR keyframe before resuming normal feeding.
                    seekingKeyFrame = true
                    configData?.let { config ->
                        val idx = decoder.dequeueInputBuffer(50_000)
                        if (idx >= 0) {
                            val buf = decoder.getInputBuffer(idx)!!
                            buf.clear()
                            buf.put(config)
                            decoder.queueInputBuffer(idx, 0, config.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                            log("Re-fed CONFIG after flush")
                        } else {
                            logE("Cannot re-feed CONFIG after flush — dequeueInputBuffer failed")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE("Error feeding buffer to decoder: ${e.message}")
        }
    }

    private fun drainOutput(decoder: MediaCodec) {
        if (!running.get()) return
        val bufferInfo = drainBufferInfo
        try {
            while (true) {
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, outputSurfaceValid) // render to surface only if valid
                    renderCount++
                    if (renderCount <= 3 || renderCount % 30 == 0L) {
                        log("Rendered frame #$renderCount size=${bufferInfo.size} flags=${bufferInfo.flags}")
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    log("Output format changed: ${decoder.outputFormat}")
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            logE("Error draining decoder output: ${e.message}")
        }
    }

    /**
     * Enqueues a received frame for decoding.
     * Called from the network thread — must not block.
     *
     * CONFIG frames are always cached, even if the decoder hasn't started yet.
     *
     * Drop strategy: never evict CONFIG or keyframes. When the queue is full:
     *  - P-frames: just drop the incoming frame (least harm)
     *  - Keyframes/CONFIG: evict the oldest P-frame from the queue to make room.
     *    Only drop a keyframe as absolute last resort (all queued frames are keyframes).
     */
    fun onFrameReceived(isConfig: Boolean, data: ByteArray) {
        receiveCount++
        val isKey = !isConfig && isKeyFrame(data)
        if (isKey) keyFramesReceived++
        // 黑屏检测：连续多个 I 帧都极小视为疑似黑屏（去重告警一次），出现正常大小 I 帧则清除。
        val wasAlerted = blackScreen.isAlerted()
        blackScreen.onSustainedBlackScreen = { onSustainedBlackScreen?.invoke() }
        blackScreen.onFrame(isKey, data.size)
        blackScreen.onSustainedBlackScreen = null
        if (blackScreen.isAlerted() && !wasAlerted) {
            logW("Suspected BLACK SCREEN: ${blackScreen.streak()} consecutive tiny keyframes, latest=${data.size}B")
        } else if (wasAlerted && !blackScreen.isAlerted()) {
            log("Black screen alert cleared — keyframe=${data.size}B")
        }
        if (isConfig || isKey || receiveCount <= 3 || receiveCount % 60 == 0L) {
            log("onFrameReceived #$receiveCount isConfig=$isConfig isKey=$isKey size=${data.size} running=${running.get()} queue=${frameQueue.size}")
        }
        // Always cache CONFIG — needed to bootstrap the next decoder instance.
        // A fresh CONFIG establishes a new stream boundary, so any stale cached
        // keyframe from the previous stream must be discarded.
        if (isConfig) {
            configData = data
            cachedKeyFrame = null
        }
        // When stopped, cache the most recent IDR so start() can feed it as a
        // reference frame. P-frames are dropped (no codec to decode them).
        if (!running.get() && !isConfig) {
            if (isKey) {
                cachedKeyFrame = data
            }
            return
        }
        val frame = FrameData(isConfig, isKey, data)

        // Keyframe priority: if queue has frames, drop P-frames to make room
        if (isConfig || isKey) {
            while (!frameQueue.offer(frame)) {
                // Evict oldest P-frame. If only keyframes queued, drop oldest keyframe.
                val evicted = frameQueue.poll()
                dropCount++
                if (evicted != null && evicted.isKeyFrame) keyFramesDropped++
                if (dropCount <= 3 || dropCount % 60 == 0L) {
                    logW("Queue full — evicted for KEYFRAME #$dropCount (keys_dropped=$keyFramesDropped)")
                }
                if (evicted == null) break
            }
            return
        }

        // P-frame: try to enqueue, drop if full
        if (!frameQueue.offer(frame)) {
            dropCount++
            if (dropCount <= 5 || dropCount % 60 == 0L) {
                logW("Queue full — dropped incoming P-frame #$dropCount (receive=$receiveCount)")
            }
        }
    }

    /**
     * Switches the decoder output to a new Surface without stopping/restarting.
     * Uses MediaCodec.setOutputSurface() — available since API 23 (Android 10+ BYD).
     * Eliminates the keyframe-drop storm that happens with stop()+start().
     */
    fun switchSurface(newSurface: Surface) {
        val c = codec
        if (c == null) {
            logW("switchSurface: codec is null, ignoring")
            return
        }
        try {
            c.setOutputSurface(newSurface)
            outputSurfaceValid = true
            log("Switched decoder output to new Surface")
        } catch (e: Exception) {
            logE("switchSurface failed: ${e.message}")
        }
    }

    /** Mark the output surface invalid (destroyed). The decoder keeps running;
     *  releaseOutputBuffer's render flag is gated off until switchSurface/start
     *  re-attaches a valid surface. */
    fun invalidateSurface() {
        outputSurfaceValid = false
    }

    /**
     * Stops the decoder and releases the MediaCodec.
     *
     * Serialized against [start] by [lifecycleLock] (audit S-08). After
     * joining the feed thread, the release is only performed when the thread is
     * verifiably gone — `interrupt()` cannot break a native
     * `dequeueOutputBuffer` call, and releasing under it is the documented
     * SIGSEGV pattern this repo works around on the vd-server side with a
     * watchdog (audit S-07).
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!running.getAndSet(false)) return
            outputSurfaceValid = false
            log("Stopping decoder: fed=$frameCount rendered=$renderCount drops=$dropCount inputFails=$inputFailCount")
            resetBlackScreenState()
            frameQueue.clear()
            cachedKeyFrame = null

            val thread = feedThread
            feedThread = null
            thread?.interrupt()
            try { thread?.join(2000) } catch (_: InterruptedException) {}

            if (thread != null && thread.isAlive) {
                // The feed thread is wedged in a native MediaCodec call. It still
                // owns the codec it captured at start(); its finally will release
                // that instance once it unwinds. Releasing here would pull the
                // instance out from under a live native call — the documented
                // SIGSEGV pattern this repo works around on the vd-server side
                // with a watchdog (audit S-07). Leave the field alone; the
                // thread's finally clears nothing but does release its own ref.
                logW("Feed thread still alive after 2s join — leaving codec release to the thread's finally")
                codec = null
                return
            }

            // Normal path: the feed thread already exited, so its `finally` has
            // released the codec it captured. Releasing here again would only
            // raise IllegalStateException on an already-released instance —
            // the thread is the single owner of the release (audit S-07).
            codec = null
        }
    }

companion object {
        private const val TAG = "VideoDecoder"

        /**
         * How long the stream must stay black before [onSustainedBlackScreen]
         * fires. Long enough to ride out app-launch and VD warm-up transients,
         * short enough that a real stuck stream is recovered while the user is
         * still looking at it.
         */
        private const val BLACK_SCREEN_SUSTAIN_MS = BlackScreenDetector.BLACK_SCREEN_SUSTAIN_MS

        /**
         * Clamp a requested frame rate into the protocol's declared range
         * (audit S-L2).
         *
         * The value reaches [start] from a persisted preference and is used
         * twice as a divisor (`1000L / fps`) — `0` (reachable from a legacy or
         * hand-edited pref) raised an `ArithmeticException` inside the feed
         * thread, which killed decoding with no recovery. Kept as a pure
         * function so it is unit-testable without MediaCodec.
         */
        internal fun coerceFps(fps: Int): Int = fps.coerceIn(VideoConfig.MIN_FPS, VideoConfig.MAX_FPS)
    }
}
