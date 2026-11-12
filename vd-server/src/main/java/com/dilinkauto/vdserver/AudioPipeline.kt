package com.dilinkauto.vdserver

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import com.dilinkauto.protocol.AudioPayload
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * 音频采集 + AAC-LC 编码管线（issue #13 方案 A / 分节 S2）。
 *
 * ## 为什么能用 REMOTE_SUBMIX
 *
 * vd-server 以 **shell UID** 跑 `app_process`，shell 自带 `CAPTURE_AUDIO_OUTPUT`，因此
 * 可以直接 `AudioRecord(REMOTE_SUBMIX)` 采整机输出 —— 不需要 app 运行时授权、不需要
 * MediaProjection、不受国产 ROM 的 app 侧限制（#13 E1/E3 实测：HyperOS 14 与
 * AOSP-like 15 都放行）。代价是**手机本机静音**（MI 9 播放端 `deviceId:507` ==
 * `Ports ID:507 "Remote Submix Out"`，E9），这是已接受的预期行为。
 *
 * ## 设计约束
 *
 * - **绝不拖垮视频会话（R6）**：[start] 的任一环节失败都只记日志并返回 `false`，主循环
 *   整段 try/catch 兜底。音频是可选能力，采不到就不采。
 * - **不丢真实帧**：与 `VideoFrameSender` 的「直播优先丢 P 帧」不同，音频丢一帧就是
 *   21.333 ms 的空洞，所以这里没有丢帧策略，只有欠载补静音。
 * - **必须凑满一整帧再喂编码器**：`READ_NON_BLOCKING` 可能部分读，把半帧直接喂给
 *   `MediaCodec` 会让出帧节奏崩掉（#13 探针实测：帧率异常、播放端队列膨胀）。
 * - **CONFIG 必须先于 FRAME**：`INFO_OUTPUT_FORMAT_CHANGED` 时取 `csd-0` 发
 *   [AudioPayload.Config]，之后才允许发 [AudioPayload.Frame]；编码器重配置要重发。
 *
 * 出帧通过注入的 [Sink] 交给上层；S2 不含 socket 与播放（属 S3）。
 */
internal class AudioPipeline(
    private val spec: AudioSpec = AudioSpec.DEFAULT,
    private val sink: Sink,
    private val log: (String) -> Unit = { PipeLog.log(it) },
    private val logErr: (String) -> Unit = { PipeLog.err(it) },
    private val clockNanos: () -> Long = { System.nanoTime() }
) : AutoCloseable {

    /** 出帧汇：S3 在这里接 9640 socket，S6 在这里数帧。 */
    internal interface Sink {
        /** 编码参数（含带外 `csd-0`）。任何一帧 FRAME 之前必须先收到它。 */
        fun onConfig(config: AudioPayload.Config)

        /** 一帧裸 AAC-LC。 */
        fun onFrame(frame: AudioPayload.Frame)
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile private var record: AudioRecord? = null

    @Volatile private var codec: MediaCodec? = null

    /** 只由采集线程访问：编码器上报的 `csd-0`。 */
    private var csd0: ByteArray? = null

    /** 只由采集线程访问：`csd-0` 有更新、还没随 CONFIG 发出去。 */
    private var csdDirty = false

    private var warnedMissingCsd = false

    private val frames = AtomicLong(0)
    private val silenceFrames = AtomicLong(0)
    private val droppedInputs = AtomicLong(0)

    /** 已交给 [Sink.onFrame] 的帧数（不含 CONFIG）。S6 的「线性增长」判据用它。 */
    val frameCount: Long get() = frames.get()

    /** 静音期补出的帧数。判据见 #13 R10 / F2。 */
    val silenceFrameCount: Long get() = silenceFrames.get()

    /** 因编码器输入缓冲不可用而丢弃的 PCM 帧数（正常应为 0）。 */
    val droppedInputCount: Long get() = droppedInputs.get()

    /**
     * 启动失败的原因（成功为 null）。留给 S3/S4 决定手机侧能力位该怎么回传 ——
     * 车机不许猜，能力位只能由采集端给出。
     */
    @Volatile
    var startFailure: String? = null
        private set

    /**
     * 试启动。[AudioRecord] 或 [MediaCodec] 任一初始化失败都只记日志并返回 `false`
     * —— 静默降级，**绝不抛给调用方**（R6）。
     */
    fun start(): Boolean {
        if (running.get()) return true
        startFailure = null

        val rec = try {
            createRecord()
        } catch (e: Exception) {
            fail("AudioRecord init failed: ${describe(e)}")
            null
        }
        if (rec == null) {
            if (startFailure == null) fail("AudioRecord unavailable")
            return false
        }

        val enc = try {
            createEncoder()
        } catch (e: Exception) {
            fail("AAC encoder init failed: ${describe(e)}")
            rec.safeRelease()
            null
        }
        if (enc == null) {
            if (startFailure == null) fail("no usable AAC encoder")
            rec.safeRelease()
            return false
        }

        // AudioRecord 建出来还不产数据：必须先 startRecording()，否则 read() 要么恒返
        // 0（只出静音帧却让 frameCount 照涨，伪装成功）要么返 ERROR_INVALID_OPERATION(-3)
        // 让采集线程首轮即死。这里任何失败都走同一套收敛（safeRelease + fail + false），
        // 绝不抛给调用方（R6）。
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                val state = rec.recordingState
                rec.safeRelease()
                enc.safeRelease()
                fail("AudioRecord startRecording failed (state=$state)")
                return false
            }
        } catch (e: Exception) {
            rec.safeRelease()
            enc.safeRelease()
            fail("AudioRecord startRecording failed: ${describe(e)}")
            return false
        }

        record = rec
        codec = enc
        running.set(true)
        thread = daemonThread("AudioCapture", start = true) {
            captureLoop(rec, enc)
        }
        log(
            "Audio: capture started (${spec.sampleRate}Hz/${spec.channelCount}ch/" +
                "${spec.bitrate}bps, ${spec.frameBytes}B/frame, " +
                "%.3ffps)".format(spec.framesPerSecond)
        )
        return true
    }

    override fun close() {
        running.set(false)
        val t = thread
        thread = null
        if (t != null) {
            runCatching { t.interrupt() }
            // 有界 join：与 VideoFrameSender.CLOSE_JOIN_TIMEOUT_MS 同一预算口径，必须
            // 小于 PipelineServer.cleanup() 给管线线程的 2s。
            runCatching { t.join(CLOSE_JOIN_TIMEOUT_MS) }
        }
        record?.safeRelease()
        record = null
        // safeRelease 内部已经 runCatching { stop() }，无需再补一次 stop()。
        codec?.safeRelease()
        codec = null
        log("Audio: capture stopped (frames=${frames.get()}, silence=${silenceFrames.get()})")
    }

    private fun fail(reason: String) {
        startFailure = reason
        logErr("Audio: $reason")
    }

    // ── 初始化 ──

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        val channelMask = if (spec.channelCount == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBytes = AudioRecord.getMinBufferSize(
            spec.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            logErr("Audio: getMinBufferSize returned $minBytes")
            return null
        }
        // 采集缓冲至少装 4 帧，给 ROM 调度抖动留余量（读取走非阻塞，缓冲大小只影响
        // 「一次能取多少」，不影响端到端延迟）。
        val bufferBytes = maxOf(minBytes, spec.frameBytes * RECORD_BUFFER_FRAMES)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(spec.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            logErr("Audio: AudioRecord state=${rec.state} (REMOTE_SUBMIX unavailable)")
            rec.safeRelease()
            return null
        }
        log("Audio: AudioRecord ready (minBuffer=$minBytes, buffer=$bufferBytes)")
        return rec
    }

    /**
     * 逐个真尝试 AAC 编码器，直到有一个能 `configure` + `start`。
     *
     * 与视频侧同一个教训：盲取框架排序里的第一个组件会撞上 ROM 里的陈旧实现（MI 9 的
     * `OMX.qcom.video.encoder.avc` 在 configure 就抛**空 message** 的 `CodecException`）。
     * 所以这里复用视频的同一份排序策略 [orderEncoderCandidates]（非纯软实现优先、同组
     * `c2.` 优先于 `OMX.`、稳定排序），再用逐个真尝试兜底。
     */
    private fun createEncoder(): MediaCodec? {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, spec.sampleRate, spec.channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate)
            // 输入缓冲给两帧的余量，避免部分读场景下编码器频繁要缓冲。
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, spec.frameBytes * 2)
        }
        val tried = mutableListOf<String>()
        for (name in orderEncoderCandidates(audioEncoderCandidates())) {
            tried += name
            var candidate: MediaCodec? = null
            try {
                candidate = MediaCodec.createByCodecName(name)
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                candidate.start()
                log("Audio: encoder=$name")
                return candidate
            } catch (e: Exception) {
                logErr("Audio: encoder '$name' unusable: ${describe(e)}")
                // configure/start 失败时组件可能已经拿到 native 资源，必须显式释放：
                // ROM 的候选表常有 3~4 个 AAC 编码器，逐个失败会逐个泄漏。
                candidate?.safeRelease()
                continue
            }
        }
        logErr("Audio: no usable AAC encoder among $tried")
        return null
    }

    /** `audio/mp4a-latm` 的全部编码器候选（含纯软实现，作为最后防线）。 */
    private fun audioEncoderCandidates(): List<Pair<String, Boolean>> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
                }
            }
            .map { it.name to it.isSoftwareOnly }

    // ── 主循环 ──

    private fun captureLoop(rec: AudioRecord, enc: MediaCodec) {
        val pacing = AudioPacing(spec.frameDurationNanos)
        val info = MediaCodec.BufferInfo()
        val chunk = ByteArray(spec.frameBytes)
        val acc = ByteArray(spec.frameBytes)
        val silence = silenceFrame(spec)
        var accLen = 0
        var frameIndex = 0L

        try {
            while (running.get()) {
                val read = rec.read(chunk, 0, chunk.size, AudioRecord.READ_NON_BLOCKING)
                var didWork = false

                when {
                    read > 0 -> {
                        // 凑满一整帧再喂：非阻塞读可能只给半帧。
                        var offset = 0
                        while (offset < read) {
                            val take = minOf(spec.frameBytes - accLen, read - offset)
                            System.arraycopy(chunk, offset, acc, accLen, take)
                            accLen += take
                            offset += take
                            if (accLen == spec.frameBytes) {
                                queuePcm(enc, acc, accLen, frameIndex)
                                frameIndex++
                                accLen = 0
                                didWork = true
                                pacing.onRealData(clockNanos())
                            }
                        }
                    }

                    read == 0 -> {
                        // 无数据（所有 player 消失）：按 21.333 ms 节拍补静音帧（R10）。
                        val due = pacing.silenceFramesDue(clockNanos())
                        repeat(due) {
                            queuePcm(enc, silence, silence.size, frameIndex)
                            frameIndex++
                            silenceFrames.incrementAndGet()
                            didWork = true
                        }
                    }

                    else -> {
                        // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT
                        logErr("Audio: record.read returned $read, stopping capture")
                        return
                    }
                }

                if (drainOutput(enc, info)) didWork = true

                // 一轮什么都没做就别空转烧 CPU：静音期非阻塞读会立刻返回 0。
                if (!didWork) LockSupport.parkNanos(IDLE_SLEEP_NANOS)
            }
        } catch (e: Exception) {
            logErr("Audio: capture loop died: ${describe(e)}")
        } catch (e: Throwable) {
            // OutOfMemoryError / 编码器 native 崩坏：记一行就走，绝不穿透到会话层。
            logErr("Audio: capture loop fatal: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 把一整帧 PCM 喂给编码器；输入缓冲暂时不可用时计数并丢这一帧。 */
    private fun queuePcm(enc: MediaCodec, pcm: ByteArray, len: Int, frameIndex: Long) {
        val inputId = try {
            enc.dequeueInputBuffer(0)
        } catch (e: Exception) {
            logErr("Audio: dequeueInputBuffer failed: ${describe(e)}")
            return
        }
        if (inputId < 0) {
            droppedInputs.incrementAndGet()
            return
        }
        val buf: ByteBuffer = try {
            enc.getInputBuffer(inputId) ?: run {
                droppedInputs.incrementAndGet()
                return
            }
        } catch (e: Exception) {
            logErr("Audio: getInputBuffer failed: ${describe(e)}")
            return
        }
        buf.clear()
        // 长度由调用方保证（恒等于 frameBytes），但仍然包住：写缓冲失败若穿出去会杀掉
        // 采集线程，而且这一格 input 槽会永远拿不回来。
        try {
            buf.put(pcm, 0, len)
        } catch (e: Exception) {
            logErr("Audio: filling input buffer failed: ${describe(e)}")
            // 交回一个空缓冲把槽位还给编码器，否则每失败一次就少一格。
            runCatching { enc.queueInputBuffer(inputId, 0, 0, ptsUsForFrame(frameIndex, spec), 0) }
            droppedInputs.incrementAndGet()
            return
        }
        try {
            enc.queueInputBuffer(inputId, 0, len, ptsUsForFrame(frameIndex, spec), 0)
        } catch (e: Exception) {
            logErr("Audio: queueInputBuffer failed: ${describe(e)}")
        }
    }

    /**
     * 排空编码器输出。返回是否处理过任何东西（供调用方判断要不要睡一下）。
     *
     * `csd-0` 与「待发 CONFIG」的状态必须跨轮保存，否则 `INFO_OUTPUT_FORMAT_CHANGED`
     * 之后每一轮都会丢掉 `csd`、永远发不出 CONFIG。
     */
    private fun drainOutput(enc: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        var handled = false
        while (true) {
            val outputId = try {
                enc.dequeueOutputBuffer(info, 0)
            } catch (e: Exception) {
                logErr("Audio: dequeueOutputBuffer failed: ${describe(e)}")
                return handled
            }
            when {
                outputId == MediaCodec.INFO_TRY_AGAIN_LATER -> return handled

                outputId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    csd0 = readCsd0(enc)
                    // 每次格式变化都要重发 CONFIG（编码器重配置后解码器需重新初始化）。
                    csdDirty = true
                    handled = true
                }

                outputId >= 0 -> {
                    handled = true
                    emitFrame(enc, outputId, info)
                }
            }
        }
    }

    private fun emitFrame(enc: MediaCodec, outputId: Int, info: MediaCodec.BufferInfo) {
        val outBuf = try {
            enc.getOutputBuffer(outputId)
        } catch (e: Exception) {
            logErr("Audio: getOutputBuffer failed: ${describe(e)}")
            null
        }
        try {
            if (outBuf == null || info.size <= 0) return

            if (csdDirty) {
                val c = csd0
                if (c == null) {
                    // 没有 csd-0 就无法让对端解码器初始化；不发半成品，避免车机一直报错。
                    if (!warnedMissingCsd) {
                        warnedMissingCsd = true
                        logErr("Audio: frames arriving without csd-0, dropping until format change")
                    }
                    return
                }
                sink.onConfig(
                    AudioPayload.Config(
                        sampleRate = spec.sampleRate,
                        channelCount = spec.channelCount,
                        bitrate = spec.bitrate,
                        csd = c
                    )
                )
                csdDirty = false
                log("Audio: CONFIG sent (csd-0=${c.size}B)")
            }

            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            val data = ByteArray(info.size)
            outBuf.get(data)
            sink.onFrame(AudioPayload.Frame(ptsMs = info.presentationTimeUs / 1000L, data = data))
            frames.incrementAndGet()
        } finally {
            runCatching { enc.releaseOutputBuffer(outputId, false) }
        }
    }

    /** `INFO_OUTPUT_FORMAT_CHANGED` 之后才能读到 `csd-0`（AAC 实测 2 B）。 */
    private fun readCsd0(enc: MediaCodec): ByteArray? = try {
        val buf = enc.outputFormat.getByteBuffer("csd-0")
        if (buf == null) {
            logErr("Audio: output format has no csd-0")
            null
        } else {
            ByteArray(buf.remaining()).also { buf.get(it) }
        }
    } catch (e: Exception) {
        logErr("Audio: reading csd-0 failed: ${describe(e)}")
        null
    }

    companion object {
        /** 采集缓冲至少 4 帧。 */
        private const val RECORD_BUFFER_FRAMES = 4

        /** 空闲时的睡眠粒度；静音期非阻塞读会立刻返回 0，不睡就是空转。 */
        private const val IDLE_SLEEP_NANOS = 1_000_000L

        /** close() 等采集线程退出的上限，对齐 `VideoFrameSender.CLOSE_JOIN_TIMEOUT_MS`。 */
        private const val CLOSE_JOIN_TIMEOUT_MS = 1500L
    }
}

/**
 * 把异常变成可读的一行。
 *
 * `CodecException` 的 message 可能是**空串**（MI 9 的 `OMX.qcom.video.encoder.avc` 就是），
 * 只打 message 会得到一行 `Fatal: encoder: `，现场无法定位 —— 所以空 message 回退到类名。
 */
internal fun describe(e: Throwable): String =
    e.message?.trim().takeUnless { it.isNullOrEmpty() } ?: e.javaClass.simpleName

/** 释放 `AudioRecord`，任何异常都不穿透。 */
private fun AudioRecord.safeRelease() {
    runCatching { stop() }
    runCatching { release() }
}

/** 释放 `MediaCodec`，任何异常都不穿透（未 start 过的编解码器 `stop()` 会抛，已被吞掉）。 */
private fun MediaCodec.safeRelease() {
    runCatching { stop() }
    runCatching { release() }
}
