package com.dilinkauto.server.decoder

/**
 * 解码器是否仍在出帧的判定器（issue #11）。
 *
 * 背景：持续黑屏检测的纯码流判据（[com.dilinkauto.protocol.BlackScreenDetector]）
 * 分不清「内容本身是黑」与「管线卡死」——两者都表现为连续极小 I 帧。切应用后
 * app 的启动/加载页是大面积纯色，编码器照样输出 ~200B 的关键帧，车机端 3s 窗口
 * 一到就误判为管线故障、主动重握手重启 VD，用户看到的是「断联」10 秒左右。
 *
 * 判据：黑屏满窗时如果最近 [windowMs] 内解码出的帧数还在增长，说明解码器一直在
 * 正常出帧 —— 那就是内容黑，不该升级；只有出帧停摆才是真故障。与桌面端
 * `VideoDecodePipeline.decodingAlive()` 同一语义。
 *
 * 线程模型：调用方在**网络线程**（黑屏检测）里问询，出帧计数在**喂帧线程**里自增，
 * 所以 [isAlive] 与 [reset] 互斥；计数本身只用于比较是否变化，不必严格原子。
 */
class DecodeLiveness(
    /** 最近这么久内出帧计数没变化，就算解码停摆。 */
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    private var lastCount = 0L
    private var lastProgressMs = 0L

    /**
     * 解码是否仍在出帧。
     *
     * @param count 累计出帧数（车机端为 `renderCount`）。
     * @param nowMs 单调毫秒时钟（车机端用 `SystemClock.elapsedRealtime()`）。
     * @return `true` = 最近 [windowMs] 内有增长（或还没有基线）；`false` = 停摆。
     */
    @Synchronized
    fun isAlive(count: Long, nowMs: Long): Boolean {
        if (count != lastCount) {
            lastCount = count
            lastProgressMs = nowMs
            return true
        }
        // 首次采样还没有基线（会话刚开始、一帧都没解出来）：按「活着」处理，
        // 预热期不该升级；真停摆会在一个窗口之后翻成 false。
        if (lastProgressMs == 0L) {
            lastProgressMs = nowMs
            return true
        }
        return nowMs - lastProgressMs < windowMs
    }

    /** 会话边界（start/stop）复位，让新会话重新建立基线。 */
    @Synchronized
    fun reset() {
        lastCount = 0L
        lastProgressMs = 0L
    }

    companion object {
        /**
         * 2s：正常 30fps 下至少该解出 60 帧，2s 一帧都没有才是真停摆，而不是帧率抖动。
         * 比黑屏窗口（3s）短，所以「内容黑但仍在解码」永远进不了升级分支。
         */
        const val DEFAULT_WINDOW_MS = 2_000L
    }
}
