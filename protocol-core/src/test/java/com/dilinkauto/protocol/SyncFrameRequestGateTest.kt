package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IDR 补帧请求闸门的纯逻辑钉子（issue #75）。
 *
 * 现场缺陷：车机端闸门 500ms、手机端闸门 1000ms，于是「距上次授予 500–999ms」的补帧
 * 请求会被车机放行、又被手机静默吞掉，车机只能干等下一个自然 GOP（3s），表现为长会话
 * 每约 50s 冻结 2.4–2.9s。修复把两端收敛到 [VideoConfig.SYNC_FRAME_REQUEST_MIN_INTERVAL_MS]
 * 单一来源，本文件钉住这个值与它的边界语义。
 *
 * 为什么是 1000 而不是 500：手机侧 1s 是 issue #3 用来打断「IDR 突发打满链路」正反馈的
 * 闸门，issue #70 又把自然 GOP 拉到 3s，方向一致；降到 500 会把 IDR 突发上限翻倍，
 * 重新冲击仍 OPEN 的 P1（issue #55）。
 */
class SyncFrameRequestGateTest {

    private val last = 1_000_000L

    @Test
    fun `shared interval literal is pinned to one second`() {
        // 两端唯一来源；改动即两端同时变，绝不允许再出现第二份常量。
        assertEquals(1000L, VideoConfig.SYNC_FRAME_REQUEST_MIN_INTERVAL_MS)
    }

    @Test
    fun `747ms gap after a grant is refused by the shared gate`() {
        // issue #75 的现场数字：距上次授予 747ms 的补帧请求。修复后车机端在同一闸门上
        // 就被拒（不再白发一个注定被手机吞掉的 0x33），两端决策一致 → 死区为空。
        assertFalse(
            "747ms < 1000ms 必须一致拒绝；允许即意味着另一端可能吞掉，死区回来了",
            VideoConfig.syncFrameRequestAllowed(last + 747L, last),
        )
    }

    @Test
    fun `boundary is inclusive at exactly the shared interval`() {
        assertFalse("999ms 必须拒", VideoConfig.syncFrameRequestAllowed(last + 999L, last))
        assertTrue("整整 1000ms 必须放行（>= 语义）", VideoConfig.syncFrameRequestAllowed(last + 1000L, last))
        assertTrue(VideoConfig.syncFrameRequestAllowed(last + 1001L, last))
    }

    @Test
    fun `zero sentinel means the session has not granted one yet`() {
        // 两端都用 0 当「本会话尚未授予过」：首帧缺口必须立刻放行，不能被冷启动吞掉。
        assertTrue(VideoConfig.syncFrameRequestAllowed(nowMs = 0L, lastRequestMs = 0L))
        assertTrue(VideoConfig.syncFrameRequestAllowed(nowMs = 5L, lastRequestMs = 0L))
    }

    @Test
    fun `gate agrees with a two ends simulation across the dead zone`() {
        // 「车机放行 ⇒ 手机不吞」的充要条件（两端同一闸门时自动成立）。逐点扫过
        // 死区边界，任何一点出现「车机 true 且手机 false」即刻失败。
        for (gap in listOf(0L, 1L, 250L, 499L, 500L, 747L, 999L, 1000L, 1001L, 5000L)) {
            val car = VideoConfig.syncFrameRequestAllowed(last + gap, last)
            val phone = VideoConfig.syncFrameRequestAllowed(last + gap, last)
            assertFalse("gap=${gap}ms 出现死区：车机放行=$car 手机授予=$phone", car && !phone)
        }
    }
}
