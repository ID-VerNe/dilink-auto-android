package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记账口径的行为级回归（issue #205）。
 *
 * 只驱动真实 [VideoDecoder]（不碰 MediaCodec：`running` 手动置位、`logSink` 收日志），
 * 断言现场那两类「收进来了，却既不算喂入也不算丢弃」的分支：
 *
 *  1. `stop()` 清空接收队列 —— 现场 `client.log` 的 `drops` 从不包含这批帧；
 *  2. 停播期缓存的 IDR 被更新的 IDR 覆盖 —— 旧缓存永远不会被喂，`keys_drop` 却恒 0。
 *
 * 这三条用例在修复前就是红的（基线 `Stopping decoder` 行 `drops=0`、无覆盖留痕、
 * 汇总行没有 `codec generation=`），修复后转绿。
 */
class VideoDecoderIntakeAccountingTest {

    /** 4 字节起始码 + NAL type 5（IDR）。 */
    private val idrFrame = byteArrayOf(0, 0, 0, 1, 0x65, 0x0A, 0x10, 0x20)

    /** 4 字节起始码 + NAL type 1（非 IDR 的编码条带）。 */
    private val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x0A, 0x10, 0x20)

    private fun decoder(logs: MutableList<String>): VideoDecoder = VideoDecoder().apply {
        logSink = { logs += it }
    }

    @Test
    fun `stop 清空队列时未喂的帧必须记入 drops`() {
        val logs = mutableListOf<String>()
        val d = decoder(logs)
        d.running.set(true)
        d.onFrameReceived(isConfig = false, data = idrFrame)
        assertEquals("关键帧必须入队", 1, d.queuedFrames)

        d.stop()

        val line = logs.last { it.contains("Stopping decoder") }
        assertTrue("stop 丢弃的队列帧必须计入 drops，实际：$line", line.contains("drops=1"))
    }

    @Test
    fun `缓存 IDR 被新 IDR 覆盖时必须留痕`() {
        val logs = mutableListOf<String>()
        val d = decoder(logs)
        // 停播期（start 之前）收到两个 IDR：缓存只留得下最后一个，前一个永远喂不进去。
        d.onFrameReceived(isConfig = false, data = idrFrame)
        d.onFrameReceived(isConfig = false, data = idrFrame)

        assertTrue(
            "被覆盖的缓存 IDR 必须留下可 grep 的痕迹，实际日志：$logs",
            logs.any { it.contains("Cached KEYFRAME replaced") },
        )
    }

    @Test
    fun `停播期覆盖丢弃的 IDR 必须与 in-stream 丢帧分账`() {
        val logs = mutableListOf<String>()
        val d = decoder(logs)
        // 停播期（start 之前）收到两个 IDR：第一个被第二个覆盖。首连等 Surface 28.7 秒
        // 期间 9 个 IDR 全部属于这一类丢弃（issue #229）—— 它们丢在「解码器还没起来」，
        // 与码流内的丢帧（队列驱逐、喂入失败）语义完全不同，混在同一个 keys_drop 里
        // 现场无法归因。
        d.onFrameReceived(isConfig = false, data = idrFrame)
        d.onFrameReceived(isConfig = false, data = idrFrame)

        val s = d.intake.snapshot()
        assertEquals(
            "覆盖丢弃必须记入停播期分项，in-stream keyDropped 应为 0，实际：$s",
            0L,
            s.keyDropped,
        )
        assertEquals(
            "停播期分项必须记 1（第二个 IDR 覆盖第一个缓存），实际：$s",
            1L,
            s.keyDroppedBeforeStart,
        )
        assertEquals(
            "总账 dropped 不变式不动（全量侧不拆分），实际：$s",
            1L,
            s.dropped,
        )
        assertNull("分账后总账必须仍平，实际：${d.intake.violationText()}", d.intake.violationText())
    }

    @Test
    fun `Stopping decoder 汇总行必须带 codec generation 便于配对`() {
        val logs = mutableListOf<String>()
        val d = decoder(logs)
        d.running.set(true)

        d.stop()

        val line = logs.last { it.contains("Stopping decoder") }
        assertTrue("配对锚点缺失，实际：$line", line.contains("codec generation="))
    }

    @Test
    fun `停播期到达的 P 帧不会占用队列`() {
        val logs = mutableListOf<String>()
        val d = decoder(logs)
        d.running.set(false)

        d.onFrameReceived(isConfig = false, data = pFrame)

        assertEquals("停播期没有解码器可解，P 帧不得入队", 0, d.queuedFrames)
        assertFalse("停播期缓存只对 IDR 生效", logs.any { it.contains("Cached KEYFRAME") })
    }
}
