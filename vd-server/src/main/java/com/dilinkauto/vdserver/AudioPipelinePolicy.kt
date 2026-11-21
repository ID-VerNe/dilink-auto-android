package com.dilinkauto.vdserver

/**
 * issue #252：`read()==0` 补静音前的门控 —— 半帧还在 `acc` 里时先别补。
 *
 * ## 多发一帧的根因
 *
 * #179 修复消费侧后，车机稳定消费 46.6 帧/秒，但发送侧实测 47.2~47.8 帧/秒（理论
 * 46.875，见 [AudioSpec.framesPerSecond]），jitter 队列每秒仍丢 0.5~1.2 帧。
 *
 * `AudioPipeline.captureLoop` 在 `read()==0` 时只要 pacing 到期就补一整帧静音，
 * 完全不看 `accLen`：非阻塞读吐了半帧、剩下半帧还在路上时，先补一帧静音；紧接着
 * 真实采样凑满 `acc` 又发一帧 → 同一个 21.333 ms 窗口里发了两帧。
 *
 * ## 判据
 *
 * 只有当「还有残帧没凑满」且「距上次真实数据不足 [AUDIO_SILENCE_GRACE_FRAMES] 拍」
 * 时才推迟。宽限期一过说明不是半帧延迟而是真静音（所有 player 消失），必须立刻按
 * 节拍补帧（#13 R10：不补就 88 星饿 / 706 ms 恢复）。
 *
 * 首次采集时 `lastRealNanos = 0L`，`sinceLastRealNanos` 是个极大的数，判据自然为
 * false —— 从未读过真实数据时不会推迟。
 */
internal fun shouldDeferSilencePadding(
    pendingBytes: Int,
    sinceLastRealNanos: Long,
    frameDurationNanos: Long,
    graceFrames: Int = AUDIO_SILENCE_GRACE_FRAMES,
): Boolean = pendingBytes > 0 && sinceLastRealNanos < frameDurationNanos * graceFrames

/**
 * 补静音宽限期：2 拍约 42.7 ms（48 kHz / 1024 samples 下 2 × 21.333 ms）。
 *
 * 取 2 而不是更大：它必须明显小于车机 jitter 队列的 16 帧预算（约 341 ms），
 * 否则真静音时补帧被拖太久，播放端会先饿死；2 拍足够覆盖「半帧晚到一拍」的
 * 常见抖动（现场 `readZeroPad` 窗口里补帧与真实帧交替出现即是此形态）。
 */
internal const val AUDIO_SILENCE_GRACE_FRAMES = 2
