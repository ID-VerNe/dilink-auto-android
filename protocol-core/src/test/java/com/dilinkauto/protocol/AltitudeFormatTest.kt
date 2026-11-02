package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死车机主屏海拔的纯判定 / 平滑 / 格式化逻辑（issue #40）。
 *
 * 覆盖过期定位拒收、EMA 收敛与首样本直采、负海拔、null → `—`、取整边界。
 */
class AltitudeFormatTest {

    // ─── acceptLocation ───

    @Test
    fun rejectsNullAltitude() {
        assertFalse(AltitudeFormat.acceptLocation(null, ageMs = 0L))
    }

    @Test
    fun rejectsNullAge() {
        // 调用方无法判定年龄（例如 time 字段缺失）→ 保守拒收。
        assertFalse(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = null))
    }

    @Test
    fun acceptsFreshLocation() {
        assertTrue(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = 5_000L))
    }

    @Test
    fun rejectsStaleLocation() {
        // 11s > 10s 上限 → 过期拒收（地库失锁后缓存定位冒充实时）。
        assertFalse(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = 11_000L))
    }

    @Test
    fun acceptsLocationAtExactAgeBoundary() {
        // 10s 恰好等于上限 → 仍可用（含边界）。
        assertTrue(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = 10_000L))
    }

    @Test
    fun negativeAgeTreatedAsZero() {
        // 时钟回拨导致 ageMs 为负 → 视为「刚好现在」，不拒收。
        assertTrue(AltitudeFormat.acceptLocation(altitude = 100.0, ageMs = -3_000L))
    }

    // ─── emaSmooth ───

    @Test
    fun firstSampleAdoptedDirectly() {
        // 无历史时首样本直采，不从 0 拉起。
        assertEquals(123.0, AltitudeFormat.emaSmooth(prev = null, sample = 123.0), 0.001)
    }

    @Test
    fun emaConvergesTowardNewSamples() {
        // prev=100, sample=200, alpha=0.2 -> 100*0.8 + 200*0.2 = 120
        assertEquals(120.0, AltitudeFormat.emaSmooth(prev = 100.0, sample = 200.0, alpha = 0.2f), 0.001)
    }

    @Test
    fun emaWithAlphaOneFollowsSampleExactly() {
        assertEquals(200.0, AltitudeFormat.emaSmooth(prev = 100.0, sample = 200.0, alpha = 1f), 0.001)
    }

    @Test
    fun emaClampsAlphaAboveOne() {
        // alpha > 1 被钳到 1，等价于完全跟随新样本。
        assertEquals(200.0, AltitudeFormat.emaSmooth(prev = 100.0, sample = 200.0, alpha = 5f), 0.001)
    }

    // ─── formatAltitude ───

    @Test
    fun nullRendersAsDash() {
        assertEquals("—", AltitudeFormat.formatAltitude(meters = null as Double?))
        assertEquals("—", AltitudeFormat.formatAltitude(meters = null as Int?))
    }

    @Test
    fun positiveAltitudeRoundsToInt() {
        // 1234.5 -> 1235（四舍五入）。
        assertEquals("1235m", AltitudeFormat.formatAltitude(1234.5))
    }

    @Test
    fun negativeAltitudeRoundsTowardZeroForFraction() {
        // -12.4 -> -12（四舍五入到最接近的整数）。
        assertEquals("-12m", AltitudeFormat.formatAltitude(-12.4))
    }

    @Test
    fun negativeAltitudeWithLargeFractionRoundsDown() {
        // -12.6 -> -13。
        assertEquals("-13m", AltitudeFormat.formatAltitude(-12.6))
    }

    @Test
    fun seaLevelRendersAsZero() {
        assertEquals("0m", AltitudeFormat.formatAltitude(0.0))
        assertEquals("0m", AltitudeFormat.formatAltitude(0))
    }

    @Test
    fun intOverloadMatchesDoubleOverload() {
        // 整数重载与 Double 重载（已取整）输出一致。
        assertEquals(AltitudeFormat.formatAltitude(1235.0), AltitudeFormat.formatAltitude(1235))
    }
}
