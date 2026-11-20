package com.dilinkauto.vdserver

/**
 * 发送侧墙钟预算（纯逻辑，单测见 `AudioSendPacerTest`）。
 *
 * ## 为什么需要（issue #252 第二代）
 *
 * 第一代修复（#253）只在「半帧还在 `acc` 里」时推迟补静音，但每一拍的发送决策仍然
 * 与墙钟无关：真实帧凑满就立刻喂编码器，静音帧按「距上次真实数据多久」单独补，
 * 两者之间没有任何累计上限。现场 6 分钟会话的收尾行
 * `frames=17524 silence=466 realZero=17058` 正是这个形态 —— 真实帧与补静音帧相加
 * （17524 帧 ≈ 47.7~48.1 帧/秒）超过了 AAC-LC 1024 样本 @48 kHz 的 46.875 帧/秒，
 * 车机按自己的时钟消费，jitter 队列因此每秒涨一帧左右。
 *
 * ## 判据
 *
 * 以「第一帧放行时刻」为基准，累计允许帧数 = `elapsed / frameDuration + 1`：
 *
 * - 真实数据早到 → 等一拍再发（在 `AudioPipeline` 的待发槽里延迟，不丢）；
 * - 真实数据迟到 → 由补静音帧占住这一拍（是否算真静音由
 *   [shouldDeferSilencePadding] 决定，#253 判据原样保留）；
 * - 任何时候 `已放行帧数 ≤ 墙钟 / 21.333ms + 1`，与接收端播放时钟同源。
 *
 * ## 落后封顶（沿用 #13 R13）
 *
 * `read` 被阻塞几百毫秒后不逐帧追平：一次最多放行 [maxCatchUpFrames] 帧，随后把基准
 * 对齐到当前时刻 —— 宁可让时间轴留一小段空隙，也不把积压一次性灌进编码器与车机队列。
 */
internal class AudioSendPacer(
    private val frameDurationNanos: Long,
    private val maxCatchUpFrames: Int = DEFAULT_MAX_CATCH_UP_FRAMES,
) {
    init {
        require(frameDurationNanos > 0) { "frameDurationNanos must be > 0: $frameDurationNanos" }
        require(maxCatchUpFrames > 0) { "maxCatchUpFrames must be > 0: $maxCatchUpFrames" }
    }

    /** 基准时刻；[NOT_STARTED] 表示还没放行过任何一帧。 */
    private var windowStartNanos = NOT_STARTED

    /** 自 [windowStartNanos] 起已放行的帧数（真实 + 补静音）。 */
    private var grantedFrames = 0L

    /** 累计放行帧数，供诊断日志使用。 */
    val sentFrames: Long get() = grantedFrames

    /**
     * 申请一个发送名额：允许则返回 true（调用方**必须**真的发一帧），否则 false。
     *
     * 首次调用总是放行：没有真实数据时也要靠补静音帧把播放端喂起来（#13 R10）。
     */
    fun tryAcquire(nowNanos: Long): Boolean {
        if (windowStartNanos == NOT_STARTED) {
            windowStartNanos = nowNanos
            grantedFrames = 1
            return true
        }
        var allowed = (nowNanos - windowStartNanos) / frameDurationNanos + 1
        if (allowed - grantedFrames > maxCatchUpFrames) {
            // 落后超过封顶：放弃追平，把基准对齐到「现在」，本轮只补封顶帧数。
            windowStartNanos =
                nowNanos - (grantedFrames + maxCatchUpFrames - 1) * frameDurationNanos
            allowed = grantedFrames + maxCatchUpFrames
        }
        if (grantedFrames >= allowed) return false
        grantedFrames++
        return true
    }

    companion object {
        private const val NOT_STARTED = -1L

        /**
         * 最多连补 2 帧（约 43 ms）：与 [AUDIO_SILENCE_GRACE_FRAMES] 同一量级，远小于
         * 车机 jitter 队列的 16 帧预算。
         */
        const val DEFAULT_MAX_CATCH_UP_FRAMES = 2
    }
}
