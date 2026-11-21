package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 issue #252（第二代）的发送预算契约：[AudioSendPacer]。
 *
 * 背景：#253 只让「半帧未凑满」时推迟补静音，真实帧仍然是凑满就发、静音帧另外计拍，
 * 两者相加没有上限，现场仍是 47.2~47.8 帧/秒（理论 46.875）。这里钉住的判据是：
 * **任何时刻已放行帧数 ≤ 墙钟 / frameDuration + 1**，与输入快慢无关。
 */
class AudioSendPacerTest {

    private val spec = AudioSpec.DEFAULT

    /** 一拍：48 kHz / 1024 samples = 21_333_333 ns。 */
    private val frameDurationNanos = spec.frameDurationNanos
    private val ms = 1_000_000L

    @Test
    fun firstFrameIsGrantedImmediatelyThenOnePerBeat() {
        val pacer = AudioSendPacer(frameDurationNanos)
        assertTrue("首帧必须立刻放行，否则静音期起不了拍", pacer.tryAcquire(0L))
        assertFalse("同一拍不能连发两帧", pacer.tryAcquire(0L))
        assertFalse(pacer.tryAcquire(frameDurationNanos - 1))
        assertTrue(pacer.tryAcquire(frameDurationNanos))
        assertFalse(pacer.tryAcquire(2 * frameDurationNanos - 1))
        assertTrue(pacer.tryAcquire(2 * frameDurationNanos))
        assertEquals(3L, pacer.sentFrames)
    }

    /**
     * 核心性质（issue #252 验收线）：输入 PCM 比理论多 1.1% 时，300 s 窗口内发送帧数
     * 不得超过理论值 + 1。旧实现里真实帧「凑满就发」不受约束，这条会红。
     */
    @Test
    fun surplusInputNeverSendsMoreThanTheWallClockAllows() {
        val pacer = AudioSendPacer(frameDurationNanos)
        val windowNanos = 300L * 1_000_000_000L
        val stepNanos = ms
        // 生产者比墙钟快 1.1%：21.101 ms 就有一帧真实 PCM 可发（≈ 47.39 帧/秒）。
        val produceEveryNanos = frameDurationNanos * 1000L / 1011L

        var now = 0L
        var produced = 0L
        var sent = 0L
        var nextProduceNanos = 0L
        while (now <= windowNanos) {
            if (now >= nextProduceNanos) {
                produced++
                nextProduceNanos += produceEveryNanos
            }
            // 有真实帧待发时才申请名额；申请到就等于发出去。
            if (produced > sent && pacer.tryAcquire(now)) sent++
            now += stepNanos
        }

        val theoretical = windowNanos / frameDurationNanos + 1 // 14_063
        assertEquals("输入超 1.1% 必须按墙钟放行", 14_063L, theoretical)
        assertTrue("发送帧数 $sent 不得超过理论值 + 1 ($theoretical)", sent <= theoretical)
        assertEquals("不能修成欠发：预算到期就要发", theoretical, sent)
        assertTrue(
            "生产 $produced 帧、只发 $sent 帧 —— 多出来的留在采集缓冲里，不再变成帧",
            produced > theoretical + 100
        )
    }

    /** 反向性质：输入恰好等于墙钟时不能少发（静音期也一样要按拍喂播放端，#13 R10）。 */
    @Test
    fun silentCaptureGetsExactlyOneFramePerBeat() {
        val pacer = AudioSendPacer(frameDurationNanos)
        var now = 0L
        var sent = 0L
        while (now <= 10L * 1_000_000_000L) {
            if (pacer.tryAcquire(now)) sent++
            now += ms
        }
        // 10 s / 21.333 ms = 468.75 拍 ⇒ 首帧 + 468 拍。
        assertEquals(469L, sent)
    }

    /**
     * #13 R13 墓碑：`read` 被阻塞几百毫秒后不能把积压一次性灌进编码器 —— 一次最多补
     * 2 帧，随后把基准对齐到当前时刻。
     */
    @Test
    fun catchUpIsCappedAfterALongStall() {
        val pacer = AudioSendPacer(frameDurationNanos, maxCatchUpFrames = 2)
        assertTrue(pacer.tryAcquire(0L))
        val stallNanos = 10L * frameDurationNanos
        var caughtUp = 0
        while (pacer.tryAcquire(stallNanos)) caughtUp++
        assertEquals("落后 10 拍只补封顶的 2 帧", 2, caughtUp)
        // 对齐到当前时刻：下一拍在 stall + 1 帧，不在写满落后的时间轴上。
        assertFalse(pacer.tryAcquire(stallNanos + frameDurationNanos - 1))
        assertTrue(pacer.tryAcquire(stallNanos + frameDurationNanos))
    }

    @Test
    fun catchUpCapIsConfigurable() {
        val pacer = AudioSendPacer(frameDurationNanos, maxCatchUpFrames = 1)
        assertTrue(pacer.tryAcquire(0L))
        assertTrue(pacer.tryAcquire(7L * frameDurationNanos))
        assertFalse(pacer.tryAcquire(7L * frameDurationNanos))
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertThrows<IllegalArgumentException> { AudioSendPacer(0L) }
        assertThrows<IllegalArgumentException> { AudioSendPacer(-1L) }
        assertThrows<IllegalArgumentException> { AudioSendPacer(frameDurationNanos, maxCatchUpFrames = 0) }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        org.junit.Assert.fail("Expected ${T::class.java.simpleName} to be thrown")
    }
}
