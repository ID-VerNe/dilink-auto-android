package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlayerTimingTest {

    @Test
    fun testNormalProgressionAdvancesByFrameNanos() {
        val frameNanos = 21_333_333L // ~21.3ms for 48kHz
        val currentTick = 1_000_000_000L
        val now = 1_010_000_000L // within frame window (10ms after)

        // issue #179：准时时本拍只处理 1 帧，基准前进 1 帧（不再丢拍）。
        val plan = audioTickPlan(currentTick, now, frameNanos)
        assertEquals(1, plan.framesDue)
        assertEquals(currentTick + frameNanos, plan.nextTickNanos)
    }

    @Test
    fun testUnderrunRecoveryResetsBaselineToNow() {
        val frameNanos = 21_333_333L
        val currentTick = 1_000_000_000L
        // 500ms lag due to GC or system stutter
        val now = 1_500_000_000L

        // 落后远超 AUDIO_MAX_FRAMES_PER_TICK → 走重置分支：1 帧、基准取 now + frameNanos。
        val plan = audioTickPlan(currentTick, now, frameNanos)
        assertEquals(1, plan.framesDue)
        assertEquals(now + frameNanos, plan.nextTickNanos)
    }
}
