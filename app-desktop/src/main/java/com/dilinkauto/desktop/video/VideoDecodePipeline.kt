package com.dilinkauto.desktop.video

import com.dilinkauto.protocol.FrameCodec
import com.dilinkauto.protocol.H264NalParser
import com.dilinkauto.protocol.VideoMsg
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面端视频解码管线（Phase 1b）。
 *
 * 数据流：网络线程 [feed] → [FeedGate]（先 CONFIG、重建后等 IDR）→ [VideoStreamPipe]
 *        → 解码线程 `FFmpegFrameGrabber`（原始 H.264 码流模式）→ [onImage] 输出 BufferedImage。
 *
 * 解码器重建：`grab()` 出错或读到 EOF（会话尚未停止）时，丢弃旧管道，新建
 * gate + pipe + grabber，重放缓存的 CONFIG 并等下一个 IDR——与车机端 MediaCodec
 * flush 后的恢复策略一致。
 *
 * 硬解（Phase 5b）：构造时给 [hwaccel]（如 `"d3d11va"`）就先用硬解跑；若
 * [HardwareDecodeWatchdog] 判定"起来了但一帧都出不来"，则本次运行内永久回退软解
 * ——硬解失败通常是驱动/机器不支持，重试也不会好。
 *
 * 职责边界（SRP）：本类只管"字节 → 图像"，不碰网络、不碰窗口；输出交给上层。
 * 线程约定：[feed] 由网络线程调用，[start]/[stop] 由控制线程调用，其余都在解码线程上。
 */
class VideoDecodePipeline(
    /** 硬解加速器名（FFmpeg 的 `-hwaccel` 取值，如 "d3d11va"）；null = 纯软解。 */
    private val hwaccel: String? = null,
) {
    /** 解码线程回调，每个解码出的画面调用一次。 */
    var onImage: ((BufferedImage) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    val framesDecoded = AtomicLong()
    val decoderRebuilds = AtomicLong()

    /** 网络线程收到的帧总数（含 CONFIG）；联调时与 framesDecoded 对照，定位瓶颈在接收侧还是解码侧。 */
    val framesFed = AtomicLong()

    /** 收到的 CONFIG（SPS/PPS）次数。 */
    val configsFed = AtomicLong()

    /** 当前是否在用硬解（回退后变 false）。 */
    @Volatile
    var hardwareInUse: Boolean = hwaccel != null
        private set

    /** 硬解起不来的判定器；只有配置了 [hwaccel] 时才参与。 */
    private val hardwareWatchdog = HardwareDecodeWatchdog()

    /** 最近一次收到的 CONFIG，供解码器重建时重放。 */
    @Volatile
    private var config: ByteArray? = null

    @Volatile
    private var gate: FeedGate? = null

    @Volatile
    private var pipe: VideoStreamPipe? = null

    @Volatile
    private var running = false
    private var decodeThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        decodeThread = Thread({ decodeLoop() }, "VideoDecode").apply {
            isDaemon = true
            start()
        }
    }

    /** 网络线程调用，非阻塞：把收到的视频帧转交闸门。 */
    fun feed(frame: FrameCodec.Frame) {
        framesFed.incrementAndGet()
        val g = gate ?: return
        if (frame.messageType == VideoMsg.CONFIG) {
            configsFed.incrementAndGet()
            config = frame.payload
            g.onConfig(frame.payload)
        } else {
            g.onFrame(frame.payload, H264NalParser.isKeyFrame(frame.payload))
        }
        checkHardwareFallback()
    }

    /**
     * 硬解回退检查。放在 [feed] 里而不是另起看门狗线程：喂帧本来就是在网络线程上
     * 每秒几十次的高频路径，顺手做一次时间比较比多一个线程便宜得多。
     *
     * 触发后关掉当前管道——解码线程阻塞在 `grab()` 上的读到 EOF 就会退出并重建，
     * 而 [hardwareInUse] 已是 false，重建出来的就是软解解码器。
     */
    private fun checkHardwareFallback() {
        if (!hardwareInUse) return
        if (!hardwareWatchdog.onFrame(framesDecoded.get())) return
        fallbackToSoftware("硬件解码 ${HardwareDecodeWatchdog.DEFAULT_TIMEOUT_MS}ms 内无任何输出 —— 回退软解")
        // 只关管道，不 join 解码线程：本方法是网络线程，不能在这里阻塞等待重建
        runCatching { pipe?.close() }
    }

    /**
     * 硬解回退的落地动作（audit R3-SRP-17）：置标志 + 记日志只有这一个点。
     *
     * 两条触发路径各有自己的后续——喂帧侧关管道唤醒阻塞的读、解码线程侧
     * 交给外层重建——所以调用方保留各自的收尾动作，这里只统一状态翻转。
     */
    private fun fallbackToSoftware(reason: String) {
        hardwareInUse = false
        log(reason)
    }

    /** 停止管线：关闭管道唤醒阻塞中的 FFmpeg 读，然后等解码线程退出。 */
    fun stop() {
        if (!running) return
        running = false
        gate = null
        pipe?.close()
        decodeThread?.interrupt()
        decodeThread?.join(STOP_JOIN_TIMEOUT_MS)
        decodeThread = null
    }

    private fun decodeLoop() {
        val converter = Java2DFrameConverter()
        while (running) {
            val currentPipe = VideoStreamPipe()
            val currentGate = FeedGate(currentPipe::write, config)
            pipe = currentPipe
            gate = currentGate
            currentGate.beginStream()
            // 本次重建是否用硬解。回退之后再重建就走软解（hardwareInUse 已被置 false）。
            val useHardware = hardwareInUse
            if (useHardware) hardwareWatchdog.onStreamStarted()

            var grabber: FFmpegFrameGrabber? = null
            try {
                // 必须用双参构造（maximumSize=0）：单参构造会传 Integer.MAX_VALUE-8，从而
                // 启用 JavaCV 的 seek 模拟——FFmpeg 在 avformat_find_stream_info 中询问
                // AVSEEK_SIZE 时，它靠"skip 整个流直到 EOF"来测量长度；直播流永远没有 EOF，
                // start() 会永久阻塞并把喂入的数据全部吞掉（真机第 1 轮踩坑）。
                // maximumSize=0 表示禁用 seek（JavaCV 源码注释：disable seek and minimize startup time）。
                grabber = FFmpegFrameGrabber(currentPipe.inputStream, 0).apply {
                    setFormat("h264")                        // 原始 Annex B 码流模式
                    setPixelFormat(avutil.AV_PIX_FMT_BGR24)  // 直接出 BGR24，便于转 BufferedImage
                    // 直播流不需要默认 5s 的流信息探测，压小以免首帧延迟过大
                    setOption("analyzeduration", "1000000")
                    setOption("probesize", "500000")
                    if (useHardware) {
                        // 不设 hwaccel_output_format：让 FFmpeg 自行决定，需要时自动
                        // hwdownload 回系统内存（我们要的是 AV_PIX_FMT_BGR24）。
                        setVideoOption("hwaccel", hwaccel)
                    }
                }
                try {
                    grabber.start()
                } catch (e: Exception) {
                    // 硬解初始化失败：本次运行内不再试，交给外层重建走软解
                    if (useHardware) {
                        fallbackToSoftware("硬件解码启动失败（${e.message}）—— 回退软解")
                    }
                    throw e
                }
                log(
                    "decoder started: ${grabber.imageWidth}x${grabber.imageHeight} " +
                        "codec=${grabber.videoCodecName} hwaccel=${if (useHardware) hwaccel else "none"}"
                )

                while (running) {
                    val frame = grabber.grab()
                    if (frame == null) {
                        // 读到 EOF（管道被关闭）或流解析结束；会话仍在则走重建
                        log("grab() 返回 null：码流结束或输入被关闭")
                        break
                    }
                    if (frame.image == null) continue
                    framesDecoded.incrementAndGet()
                    onImage?.invoke(converter.getBufferedImage(frame))
                }
            } catch (e: Exception) {
                if (running) log("decode error: ${e.message}")
            } finally {
                gate = null
                pipe = null
                runCatching { grabber?.stop() }
                runCatching { grabber?.release() }
                currentPipe.close()
            }

            if (running) {
                decoderRebuilds.incrementAndGet()
                val g = currentGate
                log(
                    "decoder rebuild #${decoderRebuilds.get()}：重放 CONFIG 并等下一个 IDR " +
                        "(droppedPreConfig=${g.droppedPreConfig} skippedP=${g.skippedPFrames})"
                )
                try {
                    Thread.sleep(REBUILD_BACKOFF_MS)
                } catch (_: InterruptedException) {
                    // stop() 打断休眠：回到循环顶部退出
                }
            }
        }
        log("decode thread 退出：decoded=${framesDecoded.get()} rebuilds=${decoderRebuilds.get()}")
    }

    private fun log(message: String) {
        onLog?.invoke(message)
    }

    companion object {
        /** Windows 上的 D3D11 硬解加速器名（FFmpeg 的 `-hwaccel` 取值）。 */
        const val HWACCEL_D3D11VA = "d3d11va"

        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
        private const val REBUILD_BACKOFF_MS = 500L
    }
}