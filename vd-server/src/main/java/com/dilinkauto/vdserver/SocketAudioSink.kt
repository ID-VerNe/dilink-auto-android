package com.dilinkauto.vdserver

import com.dilinkauto.protocol.AudioMsg
import com.dilinkauto.protocol.AudioPayload
import com.dilinkauto.protocol.Channel
import com.dilinkauto.protocol.FrameCodec
import java.nio.channels.SocketChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [AudioPipeline.Sink] 的 9640 落链（issue #73 / S3-PA）。
 *
 * ## 为什么不直接同步写 socket（P1-2）
 *
 * [AudioPipeline.Sink.onFrame] 是在**采集线程**上被同步调用的（`AudioPipeline.kt`
 * 的 `emitFrame` → `sink.onFrame`）。若在这里直接 `FrameCodec.writeFrameToChannel`，
 * 链路一慢就把采集线程卡在 socket 写里：`AudioRecord` 的读取节奏被打乱，端到端
 * 延迟无界累积（音频不像视频能靠 IDR 重建，只会越积越久）。所以与
 * [VideoFrameSender] 同形——有界队列 + 唯一写线程，[onFrame] 只做入队（常数级）。
 *
 * ## 丢帧策略：丢最旧，但队头的 CONFIG 不丢（与视频不同）
 *
 * 视频是「丢 P 帧 / 关键帧优先」（参考链可由下一个 IDR 恢复）；音频每一帧等权，
 * 丢一帧就是 21.333 ms 的空洞，所以队列满时丢**最旧**的一帧，让延迟贴回当前
 * 时刻。唯一例外是队头的 CONFIG：它带 `csd-0`，掉了接收端解码器初始化不了，
 * 之后所有 FRAME 都是噪声——因此队头是 CONFIG 时改丢它后面最老的那一帧
 * （[audioEvictIndex]）。被丢的帧计入 [droppedFrames]。
 *
 * 纯 JVM 实现（socket 通过 [writeFrame] 注入），便于单测。
 */
internal class SocketAudioSink internal constructor(
    /** 队列容量（帧）。默认 48 帧 ≈ 1.02s @46.875fps 的抖动余量。 */
    private val capacity: Int,
    /** 真正的写出动作（测试注入假实现）。 */
    private val writeFrame: (FrameCodec.Frame) -> Unit,
    /** 关闭 9640 channel：解除 [FrameCodec.writeAll] 在写阻塞里的 parkNanos 自旋。 */
    private val closeChannel: () -> Unit
) : AudioPipeline.Sink, AutoCloseable {

    /** 生产构造：把帧写进 9640 的 [SocketChannel]（须为 non-blocking，见 acceptCarChannel）。 */
    constructor(channel: SocketChannel, capacity: Int = DEFAULT_CAPACITY) : this(
        capacity,
        writeFrame = { FrameCodec.writeFrameToChannel(channel, it) },
        // 用显式 try/catch 而非 runCatching：委托构造实参里不能引用实例成员，
        // 由类实现的 AutoCloseable 作用域会让 runCatching 解析成实例成员。
        closeChannel = { try { channel.close() } catch (_: Exception) {} }
    )

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    /**
     * Kotlin 的 [ArrayDeque]（非 `java.util`）—— 需要按**下标**移除（见
     * [audioEvictIndex] 的「保 CONFIG、丢它后面那帧」），`java.util.ArrayDeque`
     * 只有 `remove(Object)`。
     */
    private val queue = ArrayDeque<FrameCodec.Frame>(capacity)

    @Volatile private var running = true

    /** 写线程因异常退出后置位；调用方据此结束音频而不影响视频会话。 */
    @Volatile
    var failed = false
        private set

    /** 已成功写到 socket 的 FRAME 帧数（不含 CONFIG）。周期日志与 S6 的线性增长判据用它。 */
    @Volatile
    var sentFrames = 0L
        private set

    /** 已写到 socket 的 CONFIG 条数（正常会话 1 条；编码器重配置时才会再发）。 */
    @Volatile
    var sentConfigs = 0L
        private set

    /** 因队列满而丢弃的帧数（丢最旧）。 */
    @Volatile
    var droppedFrames = 0L
        private set

    @Volatile private var writer: Thread? = null

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    /** 启动写线程。重复调用返回已启动的实例（幂等）。 */
    fun start() {
        if (writer != null) return
        writer = Thread({ writerLoop() }, "AudioSend").apply {
            isDaemon = true
            start()
        }
    }

    /** 当前队列深度，仅用于日志。 */
    val queued: Int get() = lock.withLock { queue.size }

    override fun onConfig(config: AudioPayload.Config) {
        offer(audioConfigFrame(config))
    }

    override fun onFrame(frame: AudioPayload.Frame) {
        offer(audioDataFrame(frame))
    }

    /**
     * 入队一帧。返回 true 表示已入队；false 表示被丢弃或队列已关闭。
     *
     * 不做任何 socket I/O，对采集线程是常数级开销（仅一次加锁 + 至多一次驱逐）。
     */
    private fun offer(frame: FrameCodec.Frame): Boolean = lock.withLock {
        if (!running || failed) return false
        if (queue.size >= capacity) {
            // audioEvictIndex 只看「队头是不是 CONFIG」，不关心新来的是什么类型：
            // 音频每一帧等权，丢最旧能把延迟贴回当前时刻（与视频的丢 P 帧策略不同）。
            val idx = audioEvictIndex(queue.size, queue.first().messageType == AudioMsg.CONFIG)
            if (idx == null) {
                // 队列里只剩一个 CONFIG：宁丢新帧，也不能丢 csd。
                droppedFrames++
                return false
            }
            queue.removeAt(idx)
            droppedFrames++
        }
        queue.addLast(frame)
        notEmpty.signal()
        true
    }

    override fun close() {
        running = false
        // 写线程可能正卡在 FrameCodec.writeAll 的 parkNanos 自旋里（最长 10s），
        // 关掉 channel 才能让它立刻抛 AsynchronousCloseException 退出。
        runCatching { closeChannel() }
        lock.withLock { notEmpty.signalAll() }
        val t = writer
        writer = null
        if (t != null) {
            runCatching { t.interrupt() }
            // 有界 join：PipelineServer.cleanup() 给管线线程只有 2s 预算，
            // 音频关闭（AudioPipeline.close 的 1.5s + 这里的 1.5s）合计须小于它之外
            // 的预算——实际两者串行，但都在 cleanup 关音频那一步里，见 cleanup()。
            runCatching { t.join(CLOSE_JOIN_TIMEOUT_MS) }
        }
    }

    private fun writerLoop() {
        try {
            while (true) {
                val frame = lock.withLock {
                    while (running && queue.isEmpty()) {
                        notEmpty.await(POLL_MILLIS, TimeUnit.MILLISECONDS)
                    }
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: break

                try {
                    writeFrame(frame)
                } catch (e: Exception) {
                    failed = true
                    lock.withLock { queue.clear() }
                    PipeLog.err("AudioSend: write failed, audio disabled: ${e.message}")
                    return
                }
                if (frame.messageType == AudioMsg.CONFIG) {
                    sentConfigs++
                    continue
                }
                val n = sentFrames + 1
                sentFrames = n
                // S6 依赖的可 grep 周期行：帧数 + 时间（logClock 前缀 HH:mm:ss.SSS）。
                if (n % FRAME_LOG_INTERVAL == 0L) {
                    PipeLog.logClock(
                        "Audio: sent frames=$n configs=$sentConfigs dropped=$droppedFrames queued=${queued}"
                    )
                }
            }
        } catch (_: InterruptedException) {
            // close() 主动打断：正常退出路径。
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** 默认容量：48 帧 ≈ 1.02s。太小吸收不了抖动，太大只增加延迟。 */
        const val DEFAULT_CAPACITY = 48

        /** 写线程空闲时等待下一帧的轮询粒度（同时用来周期性检查 running）。 */
        private const val POLL_MILLIS = 100L

        /** close() 等写线程退出的上限，必须小于 PipelineServer 的 2s join 预算。 */
        private const val CLOSE_JOIN_TIMEOUT_MS = 1500L

        /** 每写出多少帧打一行周期日志（48kHz/1024samples → 120 帧 ≈ 2.56s）。 */
        const val FRAME_LOG_INTERVAL = 120L
    }
}

/**
 * Sink 的 CONFIG 回调 → 协议帧（纯函数，单测锁定线上格式）。
 *
 * `channel` 固定 [Channel.AUDIO]、`messageType` 固定 [AudioMsg.CONFIG]；payload 用
 * S1 冻结的 [AudioPayload.encodeConfig]，本函数不改它。CONFIG 必须先于任何 FRAME
 * 出现在 9640 上（裸 AAC 无 ADTS，解码器只能靠它带出的 `csd-0` 初始化）。
 */
internal fun audioConfigFrame(config: AudioPayload.Config): FrameCodec.Frame =
    FrameCodec.Frame(Channel.AUDIO, AudioMsg.CONFIG, AudioPayload.encodeConfig(config))

/** Sink 的 FRAME 回调 → 协议帧。payload 用 [AudioPayload.encodeFrame]（PTS 4B + 裸 AAC）。 */
internal fun audioDataFrame(frame: AudioPayload.Frame): FrameCodec.Frame =
    FrameCodec.Frame(Channel.AUDIO, AudioMsg.FRAME, AudioPayload.encodeFrame(frame))

/**
 * 队列满时要驱逐的下标（纯函数，单测锁定 —— 见 `SocketAudioSinkTest`）。
 *
 * 音频每一帧等权（丢一帧 = 21.333 ms 空洞），默认丢**最旧**（下标 0）。唯一例外：
 * 队头是 CONFIG 时改丢下标 1（CONFIG 后面最老的那一帧），因为 CONFIG 丢了接收端
 * 解码器就初始化不了。`size == 1` 且队头是 CONFIG 时返回 `null` —— 此时**没有**
 * 可丢的旧帧，调用方只能丢新来的帧。
 */
internal fun audioEvictIndex(size: Int, headIsConfig: Boolean): Int? = when {
    size <= 0 -> null
    size == 1 -> if (headIsConfig) null else 0
    else -> if (headIsConfig) 1 else 0
}
