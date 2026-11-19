package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定 issue #252 的补静音门控契约。
 *
 * 背景：#179 消费侧修复后车机消费 46.6 帧/秒，发送侧却是 47.2~47.8 帧/秒（理论
 * 46.875），根因是 `AudioPipeline.captureLoop` 在 `read()==0` 时不看 `accLen` 就补
 * 一整帧静音：半帧还压在 `acc` 里时先补一帧，随后真实采样凑满又发一帧 → 多发。
 *
 * 这里的判据只有一条纯函数：[shouldDeferSilencePadding]。半帧未凑满且距上次真实
 * 数据不足 [AUDIO_SILENCE_GRACE_FRAMES] 拍时推迟补静音，让真实采样自己凑满。
 */
class AudioPipelinePolicyTest {

    /** 48 kHz / 1024 samples 的一拍：21_333_333 ns。 */
    private val frameDurationNanos = AudioSpec.DEFAULT.frameDurationNanos
    private val ms = 1_000_000L

    @Test
    fun defersWhenASplitFrameIsStillOnTheWay() {
        // 半帧还在 acc 里，距上次真实数据只有 10 ms（不到一拍）⇒ 等真实采样凑满。
        assertEquals(
            true,
            shouldDeferSilencePadding(
                pendingBytes = 1024,
                sinceLastRealNanos = 10 * ms,
                frameDurationNanos = frameDurationNanos,
            ),
        )
    }

    @Test
    fun stillDefersAtTheEdgeOfTheGraceWindow() {
        // 42.6 ms < 2 拍（42.667 ms）：还在宽限期内，继续等。
        assertEquals(
            true,
            shouldDeferSilencePadding(
                pendingBytes = 1024,
                sinceLastRealNanos = 42_600_000L,
                frameDurationNanos = frameDurationNanos,
            ),
        )
    }

    @Test
    fun stopsDeferringExactlyAtTwoFrames() {
        // 42.667 ms 已到 2 拍（42_666_666 ns）上限：不再等，走 pacing 补静音。
        assertEquals(
            false,
            shouldDeferSilencePadding(
                pendingBytes = 1024,
                sinceLastRealNanos = 42_667_000L,
                frameDurationNanos = frameDurationNanos,
            ),
        )
    }

    @Test
    fun noPendingBytesNeverDefers() {
        // acc 里没有残帧就没有「半帧在路上」，不该拦着补静音。
        assertEquals(
            false,
            shouldDeferSilencePadding(
                pendingBytes = 0,
                sinceLastRealNanos = 10 * ms,
                frameDurationNanos = frameDurationNanos,
            ),
        )
    }

    @Test
    fun longSilenceNeverDefers() {
        // 100 ms 没读到任何数据：真静音，必须立刻按节拍补帧（R10）。
        assertEquals(
            false,
            shouldDeferSilencePadding(
                pendingBytes = 1024,
                sinceLastRealNanos = 100 * ms,
                frameDurationNanos = frameDurationNanos,
            ),
        )
    }
}
