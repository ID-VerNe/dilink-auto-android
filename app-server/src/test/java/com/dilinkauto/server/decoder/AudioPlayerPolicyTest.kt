package com.dilinkauto.server.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住车机端音频播放的日志契约（issue #73 分节 PB）。
 *
 * 为什么只测这一件事：`AudioPlayer` 的其余部分全是 `MediaCodec` / `AudioTrack` I/O，
 * 在无 Robolectric 的纯 JVM 单测里跑不起来（框架类返回默认值）。而**日志行格式是
 * S6 场景断言的硬契约**（`scripts/lib/asserts.sh` 的 `countF` 按固定串 `grep -F`），
 * 一旦漂移就会让 S6 永远「未命中」—— 所以把它抽成纯函数在这里用字面量钉住。
 *
 * 期望串逐字写出，不由被测函数反推。
 */
class AudioPlayerPolicyTest {

    /** 契约里 S6 会 `grep -cF` 的固定前缀。 */
    private val contractPrefix = "[AudioPlayer] decoded="

    @Test
    fun `stat line matches the frozen S6 grep contract`() {
        val line = audioPlayerStatLine(
            decoded = 469, starved = 3, underrun = 2, maxRecoverMs = 45, queue = 9
        )
        assertEquals(
            "[AudioPlayer] decoded=469 starved=3 underrun=2 maxRecoverMs=45 queue=9",
            line,
        )
    }

    @Test
    fun `stat line starts with the greppable prefix`() {
        val line = audioPlayerStatLine(0, 0, 0, 0, 0)
        assertTrue(line.startsWith(contractPrefix))
        assertEquals("[AudioPlayer] decoded=0 starved=0 underrun=0 maxRecoverMs=0 queue=0", line)
    }

    @Test
    fun `field names are stable and space separated`() {
        val line = audioPlayerStatLine(1, 2, 3, 4, 5)
        // 逐字段核对：字段名一旦改名，S6 的 assert 会静默失配
        assertTrue(line.contains(" decoded="))
        assertTrue(line.contains(" starved="))
        assertTrue(line.contains(" underrun="))
        assertTrue(line.contains(" maxRecoverMs="))
        assertTrue(line.contains(" queue="))
    }
}
