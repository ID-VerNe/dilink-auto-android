package com.dilinkauto.server.service

import com.dilinkauto.protocol.AltitudeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 issue #221 第 3 条：Altitude 的 ageMs 不得长期为负、也不得被墙钟漂移侵蚀。
 *
 * 现场 `client.log` 里 `ageMs=-1055/-850`（11-17）→ `-11080 ~ -11108`（11-18），
 * `accept=true reason=ema`：负值被钳 0 后 stale 闸门实际阈值从 10s 被冲宽到 11.1s，
 * 且漂移继续扩大后 stale 过滤会整体失效（#40 追评一的「过期缓存冒充实时」）。
 *
 * 修复把年龄判定改用单调时钟（[AltitudeFormat.monotonicAgeMs]），墙钟年龄只在
 * 相位不同步时限频记录 clockOffset（[AltitudeFormat.clockOffsetMs]）。
 */
class AltitudeAgeClockTest {

    private val ms = 1_000_000L

    @Test
    fun `monotonic age is independent of wall clock drift`() {
        // 定位在单调时钟 100s 处到达，现在是 101.2s：真实年龄 1.2s。
        // 无论车机墙钟比 GPS 慢 1s 还是 11s，结果都一样。
        assertEquals(1_200L, AltitudeFormat.monotonicAgeMs(101_200 * ms, 100_000 * ms))
    }

    @Test
    fun `monotonic age never goes negative`() {
        // 定位时间戳在未来（异常 provider / 时间戳损坏）时夹到 0，
        // 由 acceptLocation 的「刚好现在」语义处理，不产生负数日志。
        assertEquals(0L, AltitudeFormat.monotonicAgeMs(100_000 * ms, 100_500 * ms))
    }

    @Test
    fun `stale gate still rejects samples older than the threshold`() {
        // 单调时钟年龄 11.1s > MAX_LOCATION_AGE_MS（10s）：旧墙钟口径在 -11.1s 偏移下
        // 会算成 0 并放行，新口径必须拒收。
        val ageMs = AltitudeFormat.monotonicAgeMs(211_100 * ms, 200_000 * ms)
        assertEquals(11_100L, ageMs)
        assertTrue("超过 10s 的定位必须被 stale 闸门拒收", !AltitudeFormat.acceptLocation(47.0, ageMs))
    }

    @Test
    fun `fresh sample is still accepted with monotonic age`() {
        val ageMs = AltitudeFormat.monotonicAgeMs(201_000 * ms, 200_000 * ms)
        assertTrue(AltitudeFormat.acceptLocation(47.0, ageMs))
    }

    @Test
    fun `clock offset is only reported for negative wall age`() {
        // 车机墙钟比 GPS 时间戳慢 1.1s：offset 为负，供限频日志记录。
        assertEquals(-1_100L, AltitudeFormat.clockOffsetMs(nowWallMs = 1_000L, locationTimeMs = 2_100L))
        assertEquals(0L, AltitudeFormat.clockOffsetMs(nowWallMs = 2_100L, locationTimeMs = 1_000L))
        assertEquals(0L, AltitudeFormat.clockOffsetMs(nowWallMs = 1_000L, locationTimeMs = 1_000L))
    }
}
