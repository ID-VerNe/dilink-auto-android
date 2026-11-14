package com.dilinkauto.server.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.dilinkauto.protocol.AUDIO_JITTER_BUFFER_DEFAULT_MS
import com.dilinkauto.protocol.AudioJitterBuffer
import com.dilinkauto.protocol.AudioPayload
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * 播放 9640 上来的 AAC-LC 音频（issue #73 分节 PB / Refs #13）。
 *
 * ## 形状照 [VideoDecoder]
 *
 * 网络线程只入队（[onFrame] 必须非阻塞），专用播放线程按节拍消费；[start] / [stop]
 * 整段由 [lifecycleLock] 串行；`stop()` 只做**有界 join**，「线程是 release 唯一属主」
 * —— 播放线程捕获它自己创建的 `MediaCodec` / `AudioTrack`，在 `finally` 里释放，
 * `stop()` 绝不越过活着的线程去 release（与 `VideoDecoder.stop()` 同一口径）。
 *
 * ## 与 VideoDecoder 的三处关键差异
 *
 * 1. **按 21.333 ms / 46.875 fps 的节拍消费，不是 20 ms**。AAC-LC 48 kHz 每帧 1024
 *    samples = 21.333 ms，这是硬约束：按 20 ms 走会每秒多播 7~8 帧、队列膨胀
 *    （#13 §2.5 R13 实测）。
 * 2. **队列空补静音 PCM 并计 `starved`**：音频丢一帧就是 21.333 ms 空洞，不像视频
 *    能靠 IDR 重建，所以没有丢帧策略，只有「队列空 → 写一帧静音」兜底。
 * 3. **`AudioTrack.getUnderrunCount()` 单独计 `underrun`**（底层欠载），与 `starved`
 *    （应用层队列空）是两个不同口径，e2e 两个都要看。
 *
 * 抖动缓冲的状态机（目标水位、起播裁水位、队列上限丢最旧、补静音判定）抽在
 * `:protocol-core` 的 `AudioJitterBuffer` 纯函数里，本类只留 `MediaCodec` / `AudioTrack`
 * 的 I/O 与节拍。
 *
 * 所有失败都只记日志并退出播放线程，**绝不向外抛**（R6：音频失败不许影响视频会话）。
 */
class AudioPlayer(
    /**
     * 抖动缓冲毫秒值。默认 150 ms（#13 §2.5 的 15 组实测拐点），构造参数可配，
     * 由 [AudioJitterBuffer] 钳进 60~200 ms。
     *
     * 不挂到 `CarPrefs`：S4 只落地了 `audioEnabled`，本节不新增持久化项，故用
     * 构造参数 + 常量默认值。
     */
    private val bufferMs: Int = AUDIO_JITTER_BUFFER_DEFAULT_MS
) {

    /** 日志回调 —— 由 CarConnectionService 接到 `carLogSend`（同 `VideoDecoder.logSink`）。 */
    var logSink: ((String) -> Unit)? = null

    private fun emit(line: String) {
        logSink?.invoke(line) ?: Log.i(TAG, line)
    }

    private fun log(msg: String) = emit("[AudioPlayer] $msg")
    private fun logW(msg: String) = emit("[AudioPlayer][W] $msg")
    private fun logE(msg: String) = emit("[AudioPlayer][E] $msg")

    /**
     * Guards start()/stop() as whole critical sections（同 `VideoDecoder.lifecycleLock`）。
     * `MediaCodec` / `AudioTrack` 的生命周期跨线程：网络协程配 CONFIG、服务线程启停，
     * 没有这把锁会出现「stop 释放了还没人喂的 codec」之类竞态。
     */
    private val lifecycleLock = Any()

    private val running = AtomicBoolean(false)

    /** 播放线程是否在跑。 */
    val isRunning: Boolean get() = running.get()

    /** 当前会话的抖动缓冲；未 [start] 时为 null（[onFrame] 直接丢弃）。 */
    @Volatile
    private var jitter: AudioJitterBuffer<AudioPayload.Frame>? = null

    private var playbackThread: Thread? = null

    /**
     * 最近的 CONFIG。**总是缓存**，即使播放器还没 start —— CONFIG 必须先于 FRAME，
     * 丢了它后面的裸 AAC 帧都解不了。由网络线程写、播放线程读，故 @Volatile。
     */
    @Volatile
    private var config: AudioPayload.Config? = null

    /** 每次 [configure] 自增；播放线程据此重建解码器（编码器重配置时会再发 CONFIG）。 */
    @Volatile
    private var configGeneration = 0

    /** CONFIG 之前收到的 FRAME 数（正常应为 0），只用于日志限流。 */
    private val framesBeforeConfig = AtomicLong(0)

    // ── 诊断计数（e2e / 周期日志） ──

    private val decodedFrames = AtomicLong(0)
    private val starvedFrames = AtomicLong(0)
    private val silenceFrames = AtomicLong(0)
    private val maxRecoverMsValue = AtomicLong(0)

    /** 最近一次读到的 `AudioTrack.getUnderrunCount()`。 */
    @Volatile
    private var underrunValue = 0

    /** 当前队列深度（帧）。 */
    @Volatile
    private var queueDepthValue = 0

    /** 因抖动缓冲满而丢弃的最旧帧累计数。 */
    @Volatile
    private var droppedOldestValue = 0L

    /** 因抖动缓冲/输入缓冲不可用而丢掉的帧（正常为 0）。 */
    @Volatile
    private var inputDropValue = 0L

    /** 累计解码帧数（不含 CONFIG）。 */
    val decoded: Long get() = decodedFrames.get()

    /** 累计欠载补静音帧数。 */
    val starved: Long get() = starvedFrames.get()

    /** 累计解码出的全零静音帧数（issue #84 / Refs #79）。 */
    val silence: Long get() = silenceFrames.get()

    /** `AudioTrack.getUnderrunCount()` 的最新值。 */
    val underrun: Int get() = underrunValue

    /** 最长一次「欠载 → 恢复到目标水位」的耗时（ms）。 */
    val maxRecoverMs: Long get() = maxRecoverMsValue.get()

    /** 当前队列深度（帧）。 */
    val queue: Int get() = queueDepthValue

    /** 丢最旧帧累计数。 */
    val droppedOldest: Long get() = droppedOldestValue

    /** 因输入缓冲不可用而丢弃的帧累计数（诊断）。 */
    val inputDrop: Long get() = inputDropValue

    /**
     * 缓存一帧 CONFIG（网络线程调用）。可早于 [start]。
     *
     * CONFIG 带 `csd-0`，是解码器唯一的初始化来源；每次到达都让播放线程重建解码器
     * （编码器重配置时会重发）。
     */
    fun configure(config: AudioPayload.Config) {
        this.config = config
        configGeneration++
        log("CONFIG received: ${config.sampleRate}Hz/${config.channelCount}ch bitrate=${config.bitrate} csd=${config.csd.size}B")
    }

    /**
     * 入队一帧裸 AAC（网络线程调用，必须非阻塞 —— 只做一次加锁入队）。
     *
     * FRAME 先于 CONFIG 到达时丢弃（裸 AAC 无 ADTS，缺 csd 解不了），并留一条痕迹。
     */
    fun onFrame(frame: AudioPayload.Frame) {
        if (!running.get()) return
        if (config == null) {
            val n = framesBeforeConfig.incrementAndGet()
            if (n <= 3) logW("FRAME before CONFIG (${frame.data.size}B) — dropped")
            return
        }
        val buf = jitter ?: return
        val dropped = buf.enqueue(frame)
        if (dropped > 0) {
            droppedOldestValue = buf.droppedOldest
            if (droppedOldestValue <= 3 || droppedOldestValue % 60 == 0L) {
                logW("Jitter queue full — dropped $dropped oldest frame(s) (total=$droppedOldestValue, queue=${buf.size})")
            }
        }
    }

    /**
     * 启动播放线程。整段由 [lifecycleLock] 串行。
     *
     * 不在这里创建 `MediaCodec` / `AudioTrack`：它们需要 CONFIG（采样率/声道/csd），
     * 由播放线程在拿到 CONFIG 后创建，并在自己的 `finally` 里释放（release 唯一属主）。
     */
    fun start() {
        synchronized(lifecycleLock) {
            if (running.getAndSet(true)) {
                logW("start() called but already running")
                return
            }
            val buf = AudioJitterBuffer<AudioPayload.Frame>(bufferMs)
            jitter = buf
            decodedFrames.set(0)
            starvedFrames.set(0)
            silenceFrames.set(0)
            maxRecoverMsValue.set(0)
            underrunValue = 0
            queueDepthValue = 0
            droppedOldestValue = 0
            inputDropValue = 0
            framesBeforeConfig.set(0)

            log("Starting: bufferMs=${buf.bufferMs} targetFrames=${buf.targetFrames} capacity=${buf.capacity}")

            playbackThread = Thread({ playbackLoop(buf) }, "AudioPlayerPlayback").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 停止播放线程并释放 `MediaCodec` / `AudioTrack`。
     *
     * 有界 join + 「线程是 release 唯一属主」：join 超时后**不**从本线程 release，
     * 宁可让线程的 `finally` 自己收尾（同 `VideoDecoder.stop()` 的 SIGSEGV 规避）。
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!running.getAndSet(false)) return
            log("Stopping: decoded=${decodedFrames.get()} starved=${starvedFrames.get()} silence=${silenceFrames.get()} underrun=$underrunValue maxRecoverMs=${maxRecoverMsValue.get()} queue=$queueDepthValue")
            emitStatLine()
            jitter?.clear()
            jitter = null

            val t = playbackThread
            playbackThread = null
            t?.interrupt()
            try { t?.join(STOP_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {}
            if (t != null && t.isAlive) {
                // 播放线程卡在 native 调用里；它捕获的 track/decoder 由它自己的 finally
                // 释放。此处 release 会把实例从活着的 native 调用下抽走 —— 那正是本仓库
                // 在 vd-server 侧用 watchdog 规避的 SIGSEGV 形态。
                logW("Playback thread still alive after ${STOP_JOIN_TIMEOUT_MS}ms join — leaving release to the thread's finally")
            }
        }
    }

    // ── 播放线程 ──

    private fun playbackLoop(buf: AudioJitterBuffer<AudioPayload.Frame>) {
        // 8x A53 无大核：告诉调度器这是延迟敏感线程，别被后台工作饿死。
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            val firstConfig = awaitConfig() ?: run {
                logE("No CONFIG within ${CONFIG_WAIT_TIMEOUT_MS}ms — playback thread exiting")
                return
            }
            // 已应用的 CONFIG 代次：每次 configure 都会 +1，据此决定要不要重建解码器。
            var appliedGeneration = configGeneration

            codec = buildDecoder(firstConfig)
            if (codec == null) {
                logE("Failed to create AAC decoder — playback thread exiting")
                return
            }
            track = buildTrack(firstConfig)
            if (track == null) {
                logE("Failed to create AudioTrack — playback thread exiting")
                return
            }
            track.play()
            log("AudioTrack playing: ${firstConfig.sampleRate}Hz/${firstConfig.channelCount}ch")

            val pcmFrameBytes = PCM_SAMPLES_PER_FRAME * firstConfig.channelCount * BYTES_PER_SAMPLE
            // 一帧的节拍：1024 samples / 采样率。48 kHz → 21 333 333 ns（不是 20 ms）。
            val frameNanos = PCM_SAMPLES_PER_FRAME.toLong() * 1_000_000_000L / firstConfig.sampleRate
            val silence = ByteArray(pcmFrameBytes)

            primeUp(buf)

            val info = MediaCodec.BufferInfo()
            var nextTick = System.nanoTime()
            var ptsUs = 0L
            var lastStatMs = SystemClock.elapsedRealtime()
            var recovering = false
            var starveStartMs = 0L

            while (running.get()) {
                nextTick = calculateNextTick(nextTick, System.nanoTime(), frameNanos)
                val sleep = nextTick - System.nanoTime()
                if (sleep > 0) LockSupport.parkNanos(sleep)

                // CONFIG 更新（编码器重配置）→ 重建解码器，别拿着旧 csd 继续解。
                if (configGeneration != appliedGeneration) {
                    val c = config
                    if (c != null) {
                        val freshly = buildDecoder(c)
                        if (freshly != null) {
                            try { codec?.stop() } catch (_: Exception) {}
                            try { codec?.release() } catch (_: Exception) {}
                            codec = freshly
                            appliedGeneration = configGeneration
                            log("Decoder reconfigured for new CONFIG")
                        }
                    }
                }

                val dec = codec ?: break
                val trk = track

                val frame = buf.poll()
                if (frame == null) {
                    starvedFrames.incrementAndGet()
                    if (!recovering) {
                        recovering = true
                        starveStartMs = SystemClock.elapsedRealtime()
                    }
                    writePcm(trk, silence)
                } else {
                    if (feedDecoder(dec, frame, ptsUs)) {
                        ptsUs += frameNanos / 1000L
                        decodedFrames.incrementAndGet()
                    } else {
                        inputDropValue++
                    }
                    drainDecoder(dec, trk, info)
                    if (recovering && buf.size >= buf.targetFrames) {
                        recovering = false
                        val r = SystemClock.elapsedRealtime() - starveStartMs
                        if (r > maxRecoverMsValue.get()) maxRecoverMsValue.set(r)
                    }
                }

                queueDepthValue = buf.size
                if (buf.droppedOldest > droppedOldestValue) droppedOldestValue = buf.droppedOldest
                // getUnderrunCount 是廉价读取，逐拍更新让周期行与终局行都拿到当前值。
                underrunValue = trk.underrunCount

                val now = SystemClock.elapsedRealtime()
                if (now - lastStatMs >= STAT_LOG_INTERVAL_MS) {
                    lastStatMs = now
                    emitStatLine()
                }
            }
        } catch (e: Throwable) {
            // 音频绝不许把异常穿到会话层（R6）；native 崩坏也只留一行。
            logE("Playback loop died: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // 线程是它捕获的 codec/track 的唯一释放属主（无论正常退出还是抛异常）。
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
    }

    /** 等第一份 CONFIG（有界）。返回 null 表示超时/已停止。 */
    private fun awaitConfig(): AudioPayload.Config? {
        val deadline = SystemClock.elapsedRealtime() + CONFIG_WAIT_TIMEOUT_MS
        while (running.get() && SystemClock.elapsedRealtime() < deadline) {
            config?.let { return it }
            LockSupport.parkNanos(2_000_000L)
        }
        return config
    }

    /**
     * 起播前把积压裁到目标水位（探针 `rx2` 的 prime 语义）。
     *
     * 先等队列攒到 [AudioJitterBuffer.targetFrames]（有界），再裁掉多余的最旧帧 ——
     * 否则起播就背着几百毫秒延迟。
     */
    private fun primeUp(buf: AudioJitterBuffer<AudioPayload.Frame>) {
        val deadline = SystemClock.elapsedRealtime() + PRIME_TIMEOUT_MS
        while (running.get() && !buf.hasReachedTarget() && SystemClock.elapsedRealtime() < deadline) {
            LockSupport.parkNanos(2_000_000L)
        }
        val trimmed = buf.primeTrim()
        if (buf.droppedOldest > droppedOldestValue) droppedOldestValue = buf.droppedOldest
        log("Primed: depth=${buf.size}/${buf.targetFrames} trimmed=$trimmed")
    }

    private fun buildDecoder(config: AudioPayload.Config): MediaCodec? = try {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channelCount
        ).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config.csd))
        }
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
    } catch (e: Exception) {
        logE("Decoder create/configure failed: ${e.message}")
        null
    }

    private fun buildTrack(config: AudioPayload.Config): AudioTrack? = try {
        val channelMask = if (config.channelCount == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val frameBytes = PCM_SAMPLES_PER_FRAME * config.channelCount * BYTES_PER_SAMPLE
        val minBytes = AudioTrack.getMinBufferSize(
            config.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBytes, frameBytes * TRACK_BUFFER_FRAMES)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            logE("AudioTrack state=${t.state} (uninitialized)")
            t.release()
            null
        } else {
            t
        }
    } catch (e: Exception) {
        logE("AudioTrack build failed: ${e.message}")
        null
    }

    /** 把一帧裸 AAC 喂给解码器。返回 true 表示已入队。 */
    private fun feedDecoder(codec: MediaCodec, frame: AudioPayload.Frame, ptsUs: Long): Boolean = try {
        val inputId = codec.dequeueInputBuffer(0)
        if (inputId < 0) {
            false
        } else {
            val inBuf = codec.getInputBuffer(inputId)
            if (inBuf == null) {
                false
            } else {
                inBuf.clear()
                inBuf.put(frame.data)
                codec.queueInputBuffer(inputId, 0, frame.data.size, ptsUs, 0)
                true
            }
        }
    } catch (e: Exception) {
        logE("queueInputBuffer failed: ${e.message}")
        false
    }

    /** 排空解码输出并把 PCM 写进 AudioTrack。 */
    private fun drainDecoder(codec: MediaCodec, track: AudioTrack, info: MediaCodec.BufferInfo) {
        while (true) {
            val outId = try {
                codec.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                logE("dequeueOutputBuffer failed: ${e.message}")
                return
            }
            if (outId >= 0) {
                val outBuf = try { codec.getOutputBuffer(outId) } catch (_: Exception) { null }
                if (outBuf != null && info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val pcm = ByteArray(info.size)
                    outBuf.get(pcm)
                    if (isPcmSilent(pcm)) {
                        silenceFrames.incrementAndGet()
                    }
                    writePcm(track, pcm)
                }
                try { codec.releaseOutputBuffer(outId, false) } catch (_: Exception) {}
            } else if (outId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                log("Output format changed: ${codec.outputFormat}")
            } else {
                // INFO_TRY_AGAIN_LATER 等
                return
            }
        }
    }

    private fun writePcm(track: AudioTrack, pcm: ByteArray) {
        try {
            track.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            logE("AudioTrack.write failed: ${e.message}")
        }
    }

    private fun emitStatLine() {
        emit(
            audioPlayerStatLine(
                decoded = decodedFrames.get(),
                starved = starvedFrames.get(),
                underrun = underrunValue,
                maxRecoverMs = maxRecoverMsValue.get(),
                queue = queueDepthValue,
                silence = silenceFrames.get(),
            )
        )
    }

    companion object {
        private const val TAG = "AudioPlayer"

        /** PCM 一帧的 samples 数（AAC-LC 48 kHz 恒定 1024）。 */
        private const val PCM_SAMPLES_PER_FRAME = 1024
        private const val BYTES_PER_SAMPLE = 2

        /** `AudioTrack` 缓冲至少装 4 帧，给调度抖动留余量。 */
        private const val TRACK_BUFFER_FRAMES = 4

        /** 等第一份 CONFIG 的上限；超时则播放线程退出（音频静默降级）。 */
        private const val CONFIG_WAIT_TIMEOUT_MS = 3000L

        /** 起播攒水位（prime）的上限。 */
        private const val PRIME_TIMEOUT_MS = 3000L

        /** 周期日志间隔（契约 2~3 s 一行，S6 用 `grep -cF` 命中）。 */
        private const val STAT_LOG_INTERVAL_MS = 2500L

        /** stop() 等播放线程退出的上限（同 `VideoDecoder.stop()` 的 2s 预算口径）。 */
        private const val STOP_JOIN_TIMEOUT_MS = 2000L

        /**
         * Pacing clock calculation with underrun/jitter catch-up prevention.
         * If [now] has drifted past [currentNextTick] by more than [frameNanos],
         * resets the baseline to [now] so the loop does not spin without sleeping.
         */
        internal fun calculateNextTick(currentNextTick: Long, now: Long, frameNanos: Long): Long {
            val base = if (now - currentNextTick > frameNanos) now else currentNextTick
            return base + frameNanos
        }
    }
}
