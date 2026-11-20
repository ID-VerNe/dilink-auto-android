package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #202-A：VD 复用判定的纯函数契约。
 *
 * 生产路径当前是一会话一进程、进程内只有一个租约，复用分支不会命中（见
 * [VirtualDisplayLease] 类头）。这里先把判定钉死：等引擎常驻落地时，"参数一致就
 * 复用、尺寸/DPI 变化就重建、display 已消失或 Surface 已脱离不能复用"不需要重写。
 */
class VdLeasePolicyTest {

    private val request = VdLeaseParams(width = 1926, height = 1054, dpi = 234)

    private fun snapshot(
        params: VdLeaseParams = request,
        alive: Boolean = true,
        surfaceAttached: Boolean = true,
    ) = VdLeaseSnapshot(params = params, displayId = 48, alive = alive, surfaceAttached = surfaceAttached)

    @Test
    fun `参数一致且 display 存活时复用`() {
        val d = VdLeasePolicy.decide(request, snapshot())

        assertTrue(d.reuse)
        assertNull(d.reason)
    }

    @Test
    fun `尺寸变化必须重建`() {
        val d = VdLeasePolicy.decide(
            request,
            snapshot(params = VdLeaseParams(1054, 1926, 234)),
        )

        assertFalse(d.reuse)
        assertEquals(VdLeaseRebuildReason.SIZE_CHANGED, d.reason)
    }

    @Test
    fun `DPI 变化必须重建`() {
        val d = VdLeasePolicy.decide(
            request,
            snapshot(params = VdLeaseParams(1926, 1054, 480)),
        )

        assertFalse(d.reuse)
        assertEquals(VdLeaseRebuildReason.DPI_CHANGED, d.reason)
    }

    @Test
    fun `display 已消失时即使参数一致也重建`() {
        val d = VdLeasePolicy.decide(request, snapshot(alive = false))

        assertFalse(d.reuse)
        assertEquals(VdLeaseRebuildReason.DISPLAY_GONE, d.reason)
    }

    @Test
    fun `Surface 已脱离时重建`() {
        val d = VdLeasePolicy.decide(request, snapshot(surfaceAttached = false))

        assertFalse(d.reuse)
        assertEquals(VdLeaseRebuildReason.SURFACE_DETACHED, d.reason)
    }

    @Test
    fun `没有租约时走首次建屏`() {
        val d = VdLeasePolicy.decide(request, existing = null)

        assertFalse(d.reuse)
        assertEquals(VdLeaseRebuildReason.NO_LEASE, d.reason)
    }

    @Test
    fun `时间线判定 VD 先于首帧就绪`() {
        val t = VdLeaseTimeline(
            acquireStartedNanos = 1_000_000_000L,
            displayReadyNanos = 1_300_000_000L,
            firstFrameNanos = 1_450_000_000L,
        )

        assertEquals(300L, t.displayReadyAfterMs)
        assertEquals(150L, t.firstFrameAfterMs)
        assertTrue(t.readyBeforeFirstFrame)
    }

    @Test
    fun `首帧早于建屏说明调用顺序回归`() {
        val t = VdLeaseTimeline(
            acquireStartedNanos = 1_000_000_000L,
            displayReadyNanos = 1_300_000_000L,
            firstFrameNanos = 1_100_000_000L,
        )

        assertFalse(t.readyBeforeFirstFrame)
    }

    @Test
    fun `帧尚未输出时不判定为违反预建`() {
        val t = VdLeaseTimeline(
            acquireStartedNanos = 1_000_000_000L,
            displayReadyNanos = 1_300_000_000L,
            firstFrameNanos = null,
        )

        assertNull(t.firstFrameAfterMs)
        assertTrue(t.readyBeforeFirstFrame)
    }
}
