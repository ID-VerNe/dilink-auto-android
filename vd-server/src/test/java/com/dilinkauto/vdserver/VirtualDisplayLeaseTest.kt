package com.dilinkauto.vdserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #254：VD 租约的预建时机与归还契约（纯 JVM，句柄用字符串，不依赖设备）。
 *
 * 用例覆盖：
 *  - 首次 acquire = 建屏（预建），并记录 displayId 与「就绪耗时」；
 *  - 参数一致时再次 acquire = 复用，不重复建屏、不释放旧句柄；
 *  - 参数变化时 = 先归还旧句柄再建屏，避免同进程留下两个 VD；
 *  - 首帧记账幂等，且时间线证明 VD 先于首帧就绪；
 *  - release 幂等，不会重复释放。
 */
class VirtualDisplayLeaseTest {

    private class Rig {
        val logs = mutableListOf<String>()
        val released = mutableListOf<String>()
        var now = 0L
        var creates = 0

        val lease: VirtualDisplayLease<String> = VirtualDisplayLease(
            params = VdLeaseParams(1926, 1054, 234),
            log = { logs += it },
            releaseRaw = { released += it },
            clock = { now },
        )

        fun create(handle: String = "vd"): String? {
            creates++
            now += 300_000_000L
            return handle
        }
    }

    @Test
    fun `首次 acquire 建屏并记录就绪时间`() {
        val r = Rig()
        val vd = r.lease.acquire(create = { r.create() }, displayIdOf = { 49 })

        assertEquals("vd", vd)
        assertEquals(49, r.lease.displayId)
        assertEquals(1, r.creates)
        assertEquals(300L, r.lease.displayReadyAfterMs())
        assertTrue(r.released.isEmpty())
        assertTrue(r.logs.any { it.contains("[vd-lease] ready display=49") })
        assertTrue(r.logs.any { it.contains("1926x1054@234dpi") })
    }

    @Test
    fun `参数一致时复用已有租约而不是重建`() {
        val r = Rig()
        val first = r.lease.acquire(create = { r.create() }, displayIdOf = { 48 })
        r.now += 100_000_000L
        val second = r.lease.acquire(create = { r.create() }, displayIdOf = { 48 })

        assertSame(first, second)
        assertEquals(1, r.creates)
        assertTrue(r.released.isEmpty())
        assertTrue(r.logs.any { it.contains("[vd-lease] reuse display=48") })
    }

    @Test
    fun `DPI 变化时先归还旧租约再重建`() {
        val r = Rig()
        r.lease.acquire(create = { r.create(handle = "old") }, displayIdOf = { 48 })
        val rebuilt = r.lease.acquire(
            request = VdLeaseParams(1926, 1054, 480),
            create = { r.create(handle = "new") },
            displayIdOf = { 52 },
        )

        assertEquals("new", rebuilt)
        assertEquals(listOf("old"), r.released)
        assertEquals(2, r.creates)
        assertEquals(52, r.lease.displayId)
        assertTrue(r.logs.any { it.contains("rebuild: DPI_CHANGED") })
    }

    @Test
    fun `首帧只记一次且晚于建屏`() {
        val r = Rig()
        r.lease.acquire(create = { r.create() }, displayIdOf = { 48 })
        r.now += 150_000_000L
        r.lease.noteFirstFrame()
        r.now += 1_000_000_000L
        r.lease.noteFirstFrame()

        val t = r.lease.timeline()
        assertEquals(150L, t?.firstFrameAfterMs)
        assertTrue(t!!.readyBeforeFirstFrame)
        assertEquals(1, r.logs.count { it.contains("[vd-lease] first frame") })
    }

    @Test
    fun `release 幂等且清空租约`() {
        val r = Rig()
        r.lease.acquire(create = { r.create() }, displayIdOf = { 48 })
        r.lease.release()
        r.lease.release()

        assertEquals(listOf("vd"), r.released)
        assertEquals(-1, r.lease.displayId)
        assertNull(r.lease.timeline())
    }

    @Test
    fun `建屏失败时不持有租约`() {
        val r = Rig()
        val vd = r.lease.acquire(create = { null }, displayIdOf = { 48 })

        assertNull(vd)
        assertEquals(-1, r.lease.displayId)
        assertNull(r.lease.timeline())
        assertTrue(r.logs.any { it.contains("create failed") })
        assertFalse(r.logs.any { it.contains("[vd-lease] ready") })
    }
}
