package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 [BlackScreenDetector] 的持续黑屏策略（车机端 `VideoDecoder` 与桌面端
 * `VideoDecodePipeline` 共用，原先只在车机侧被覆盖）。
 *
 * 这套行为在抽取之前完全没有测试，所以这些用例同时充当"抽取本该带来的回归网"。
 * 时钟是注入的：持续窗口的时序是确定性的，不依赖墙上时钟。
 */
class BlackScreenDetectorTest {

    private fun detector(
        maxBytes: Int = 2048,
        alertStreak: Int = 3,
        sustainMs: Long = 3000L
    ): BlackScreenDetector =
        BlackScreenDetector(keyframeMaxBytes = maxBytes, alertStreak = alertStreak)
            .apply { this.sustainMs = sustainMs }

    private fun tiny(size: Int = 200) = size

    @Test
    fun nonKeyFramesAreIgnoredEntirely() {
        val d = detector()
        repeat(50) { d.onFrame(isKeyFrame = false, size = tiny(), nowMs = it * 100L) }
        assertEquals(0, d.streak())
        assertFalse(d.isAlerted())
        assertFalse(d.hasFiredRecovery())
    }

    @Test
    fun normallySizedKeyframesNeverAlert() {
        val d = detector()
        repeat(100) { d.onFrame(isKeyFrame = true, size = 4096, nowMs = it * 100L) }
        assertFalse(d.isAlerted())
        assertFalse(d.hasFiredRecovery())
        assertEquals(0, d.streak())
    }

    @Test
    fun alertFiresOnTheConfiguredStreak() {
        val d = detector(alertStreak = 3)
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 30)
        assertFalse("must not alert before the streak completes", d.isAlerted())
        d.onFrame(true, tiny(), 60)
        assertTrue(d.isAlerted())
    }

    @Test
    fun alertIsLatchedNotReFired() {
        val d = detector(alertStreak = 3, sustainMs = 3000L)
        // 20 tiny frames over 570ms: alerts, but has not yet sustained long enough.
        repeat(20) { d.onFrame(true, tiny(), it * 30L) }
        assertTrue(d.isAlerted())
        assertEquals(20, d.streak())
        assertFalse("570ms is well under the 3000ms window", d.hasFiredRecovery())
    }

    @Test
    fun aBriefBlackBurstDoesNotEscalate() {
        // The core safety property: warm-up produces a few tiny I-frames, and
        // escalating there caused the VD-leaking reconnect storm.
        var fired = 0
        val d = detector(sustainMs = 3000L).apply { onSustainedBlackScreen = { fired++ } }
        repeat(5) { d.onFrame(true, tiny(), it * 100L) }   // 400ms of black
        assertTrue("alert is expected", d.isAlerted())
        assertFalse("a short burst must NOT escalate", d.hasFiredRecovery())
        assertEquals(0, fired)
    }

    @Test
    fun sustainedBlackEscalatesExactlyOnce() {
        var fired = 0
        val d = detector(sustainMs = 3000L).apply { onSustainedBlackScreen = { fired++ } }
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 1000)
        d.onFrame(true, tiny(), 2000)
        assertEquals(0, fired)
        d.onFrame(true, tiny(), 3000)          // 3000ms sustained -> fire
        assertEquals(1, fired)
        assertTrue(d.hasFiredRecovery())
        repeat(20) { d.onFrame(true, tiny(), 3000 + it * 100L) }
        assertEquals("must never fire twice without a reset", 1, fired)
    }

    @Test
    fun escalationRequiresTheSustainWindowNotJustTheStreak() {
        var fired = 0
        val d = detector(alertStreak = 2, sustainMs = 10_000L).apply { onSustainedBlackScreen = { fired++ } }
        repeat(50) { d.onFrame(true, tiny(), it * 300L) }   // 15s of frames, but each gap resets?
        assertTrue(d.isAlerted())
        // Frames are continuous, so blackSince is only set once at streak==1.
        assertEquals(1, fired)
    }

    @Test
    fun aNormalKeyframeClearsTheStreakAndReArms() {
        var fired = 0
        val d = detector(alertStreak = 2, sustainMs = 1000L).apply { onSustainedBlackScreen = { fired++ } }
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 100)
        assertTrue(d.isAlerted())
        d.onFrame(true, 4096, 200)              // normal frame clears it
        assertFalse(d.isAlerted())
        assertEquals(0, d.streak())
        // Black again from scratch: must be able to alert and escalate once more.
        d.onFrame(true, tiny(), 300)
        d.onFrame(true, tiny(), 400)
        assertTrue(d.isAlerted())
        d.onFrame(true, tiny(), 1400)
        assertEquals(1, fired)
    }

    @Test
    fun resetReArmsEverythingIncludingTheEscalationLatch() {
        var fired = 0
        val d = detector(alertStreak = 1, sustainMs = 0L).apply { onSustainedBlackScreen = { fired++ } }
        d.onFrame(true, tiny(), 0)
        assertEquals(1, fired)
        d.reset()
        assertFalse(d.hasFiredRecovery())
        assertFalse(d.isAlerted())
        assertEquals(0, d.streak())
        d.onFrame(true, tiny(), 10)
        assertEquals("a fresh session gets a fresh chance", 2, fired)
    }

    @Test
    fun thresholdBoundaryIsExclusiveOfTheMax() {
        // size < keyframeMaxBytes counts as tiny; exactly max does not.
        val d = detector(maxBytes = 2048, alertStreak = 1, sustainMs = 0L)
        d.onFrame(true, 2048, 0)
        assertFalse("exactly at the threshold is NOT tiny", d.isAlerted())
        d.onFrame(true, 2047, 10)
        assertTrue(d.isAlerted())
    }

    @Test
    fun alertStreakOfOneFiresImmediately() {
        val d = detector(alertStreak = 1)
        d.onFrame(true, tiny(), 0)
        assertTrue(d.isAlerted())
    }

    /**
     * 默认时钟（`System.nanoTime()`）也必须可用：桌面端不注入时钟，
     * 这里确认调用默认参数不会抛异常且行为与传时钟一致。
     */
    @Test
    fun defaultClockIsUsableWithoutInjection() {
        var fired = 0
        val d = BlackScreenDetector(alertStreak = 1).apply {
            setSustainWindow(0L)
            onSustainedBlackScreen = { fired++ }
        }
        d.onFrame(isKeyFrame = true, size = 200)
        assertTrue(d.isAlerted())
        assertEquals(1, fired)
    }

    /** [BlackScreenDetector.setSustainWindow] 与直接赋值等价（会话窗口变更用）。 */
    @Test
    fun setSustainWindowReplacesPlainAssignment() {
        var fired = 0
        val d = BlackScreenDetector(alertStreak = 1).apply {
            onSustainedBlackScreen = { fired++ }
        }
        d.setSustainWindow(500L)
        d.onFrame(true, tiny(), 0)
        d.onFrame(true, tiny(), 499)
        assertEquals(0, fired)
        d.onFrame(true, tiny(), 500)
        assertEquals(1, fired)
    }
}
