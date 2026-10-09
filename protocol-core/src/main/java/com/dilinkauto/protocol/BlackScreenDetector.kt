package com.dilinkauto.protocol

/**
 * 持续黑屏检测 —— 车机端 `VideoDecoder` 与 Windows 接收端 `VideoDecodePipeline` 共用。
 *
 * 为什么落在 protocol-core：两端面对的是同一条编码链路、同一类故障 —— 手机侧的
 * VirtualDisplay 没产出（或应用没渲染）时 TCP 仍然通，接收端会**永远停在最后一帧**。
 * 判定策略是同一套，所以单点定义在这里（与 [H264NalParser] 同一模式）。
 *
 * 怎么判定：编码器对接近纯色的画面会输出极小的 I 帧，因此**连续多个极小关键帧**
 * 意味着码流是黑的。
 *
 * 两级反应，刻意不对称：
 *  - **告警**：连续 [alertStreak] 个极小关键帧后置位（[isAlerted]），只用于日志；
 *  - **升级**：只有黑屏持续满 [sustainMs] 才调用 [onSustainedBlackScreen]。
 *    应用 / VD 预热期间的短暂黑屏是正常的，在那里升级会引发重连风暴 —— 正是当初
 *    泄漏 VirtualDisplay 的那个故障模式。
 *
 * 升级每次会话最多触发一次，直到 [reset]。
 *
 * 时钟由调用方注入（[clock]）：车机端传 `SystemClock.elapsedRealtime()`，桌面端用
 * 默认的 `System.nanoTime()`。只要求"同一个单调源"，与绝对值无关；默认值不能再写
 * `SystemClock` —— 本模块是纯 JVM，不依赖 Android。
 */
class BlackScreenDetector(
    /** I 帧小于这个字节数即视为"极小"。 */
    private val keyframeMaxBytes: Int = 2 * 1024,
    /** 连续多少个极小关键帧后置告警位。 */
    private val alertStreak: Int = 3,
    /** 单调毫秒时钟；测试注入假时钟。 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    /**
     * 码流**持续**黑屏时调用一次 —— 不是第三个极小关键帧，而是连续极小关键帧满
     * [sustainMs] 之后。
     */
    var onSustainedBlackScreen: (() -> Unit)? = null

    private var tinyKeyframeStreak = 0
    private var alerted = false

    /**
     * 本轮黑屏 streak 的起点；不在 streak 中时为 null。
     *
     * 用 null 而不是 `0L` 哨兵：原来的内联实现用 `since > 0` 判断，会把"恰好从时钟
     * 0 开始的黑屏"整个吃掉，延迟一整个窗口才升级 —— 真实缺陷。
     */
    private var blackSinceMs: Long? = null
    private var recoveryFired = false

    /** 黑屏持续多久才触发 [onSustainedBlackScreen]。 */
    var sustainMs: Long = BLACK_SCREEN_SUSTAIN_MS

    /**
     * 喂一帧。
     *
     * @param isKeyFrame 是否 I 帧（NAL type 5）。
     * @param size 载荷字节数。
     * @param nowMs 单调毫秒时钟，可注入以便测试。
     */
    fun onFrame(isKeyFrame: Boolean, size: Int, nowMs: Long = clock()) {
        if (!isKeyFrame) return

        if (size >= keyframeMaxBytes) {
            // 正常大小的 I 帧清空 streak 并重新武装升级。
            alerted = false
            tinyKeyframeStreak = 0
            blackSinceMs = 0L
            recoveryFired = false
            return
        }

        tinyKeyframeStreak++
        if (blackSinceMs == null) blackSinceMs = nowMs

        if (tinyKeyframeStreak >= alertStreak && !alerted) {
            alerted = true
        }

        if (recoveryFired) return
        val since = blackSinceMs ?: return
        if (nowMs - since >= sustainMs) {
            recoveryFired = true
            onSustainedBlackScreen?.invoke()
        }
    }

    /**
     * 武装 [onSustainedBlackScreen] 的持续窗口为 [sustainMs]。
     *
     * 会话窗口变化时调用方必须调一次：保持 [sustainMs] 是普通可变字段意味着裸赋值
     * 会静默地让已构造的检测器停在默认值上。
     */
    fun setSustainWindow(ms: Long) {
        sustainMs = ms
    }

    /** 连续极小关键帧达到 [alertStreak] 后为 true。 */
    fun isAlerted(): Boolean = alerted

    /** 升级已触发（直到 [reset]）。 */
    fun hasFiredRecovery(): Boolean = recoveryFired

    /** 当前连续极小关键帧计数。 */
    fun streak(): Int = tinyKeyframeStreak

    /**
     * 为新会话重新武装：新码流得到新机会，"只升级一次"的闩锁不能跨会话存活。
     */
    fun reset() {
        tinyKeyframeStreak = 0
        alerted = false
        blackSinceMs = null
        recoveryFired = false
    }

    companion object {
        const val BLACK_SCREEN_SUSTAIN_MS = 3000L
    }
}
