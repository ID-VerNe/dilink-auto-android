package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the VD viewport math, with special weight on the 2026-10-09
 * chopped-keyboard regression: the caller must pass the phone's **real
 * physical** size. Feeding the compat-scaled value (2992 instead of 3192 for
 * the test phone) produced a VD 200px (~6.7%) narrower than the IME, which
 * Chinese ROMs hardcode to the physical width regardless of DPI — the exact
 * bug seen at DPI 120/200/300/400 alike.
 */
class VdDimensionsTest {

    /** Car 1920x1080 @160dpi, no override — the default dev-harness shape. */
    private fun defaultRequest(
        carW: Int = 1920,
        carH: Int = 1080,
        carDpi: Int = 160,
        dpiOverride: Int = 0,
    ) = HandshakeRequest(
        deviceName = "test-car",
        screenWidth = carW,
        screenHeight = carH,
        screenDpi = carDpi,
        appVersionCode = 1,
        dpiOverride = dpiOverride,
    )

    @Test
    fun realPhonePortrait_carLandscape_scalesToTruePhysicalLongEdge() {
        // 2026-10-09 的回归钉：调用方必须传真实物理尺寸而不是兼容缩放值。
        // issue #71 起放大封顶 2560，3192 的机型已经分不出 3192 / 2992（两边都被封顶），
        // 所以这条钉子改用长边 2400 的手机（兼容模式会报 2200 左右）。
        val (w, h, dpi) = VdDimensions.compute(defaultRequest(), 1080, 2400)
        assertEquals(2400, w)
        assertEquals(1350, h) // 1080 * 2400/1920
        // 车机密度 160 按放大倍数 1.25 缩放成 200（#20）
        assertEquals(200, dpi)
    }

    @Test
    fun landscapePhone_usesLongEdgeAsPhysicalWidthToo() {
        // 3192 长边超过封顶 2560 → 只放大到 2560；1080 * 2560/1920 = 1440（#71）
        val (w, h, dpi) = VdDimensions.compute(defaultRequest(), 3192, 1368)
        assertEquals(2560, w)
        assertEquals(1440, h)
        // 160 * (2560/1920) = 213，仍落在 120..320 带内且 maxSafe=320 不截断
        assertEquals(213, dpi)
    }

    @Test
    fun carWiderThanPhone_neverScalesDown() {
        // Car 2560x1440 vs phone landscape 2400x1080: the anti-crop rule only
        // scales UP; the VD stays at the car viewport.
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(carW = 2560, carH = 1440), 2400, 1080
        )
        assertEquals(2560, w)
        assertEquals(1440, h)
        assertEquals(160, dpi)
    }

    @Test
    fun portraitCar_landscapePhone_crossOrientationUsesPhoneShortEdge() {
        // Car 720x1280 (portrait) vs phone 2160x1080 (landscape): orientation
        // mismatch resolves the phone's "width" to its short edge 1080.
        // Scale 1.5 is float-exact: 720->1080, 1280->1920.
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(carW = 720, carH = 1280), 2160, 1080
        )
        assertEquals(1080, w)
        assertEquals(1920, h)
        // 放大 1.5 倍后车机密度 160 -> 240（#20）
        assertEquals(240, dpi)
    }

    @Test
    fun portraitCar_portraitPhone_scalesUpByExactFactor() {
        // 1620/1080 = 1.5 exactly: no float rounding ambiguity.
        val (w, h, _) = VdDimensions.compute(
            defaultRequest(carW = 1080, carH = 1920), 1620, 3120
        )
        assertEquals(1620, w)
        assertEquals(2880, h)
    }

    @Test
    fun oddCarViewport_isEvenAlignedBeforeScaling() {
        // 1921x1081 evens to 1920x1080 first, then follows the standard path (封顶 2560).
        val (w, h, _) = VdDimensions.compute(
            defaultRequest(carW = 1921, carH = 1081), 1368, 3192
        )
        assertEquals(2560, w)
        assertEquals(1440, h)
    }

    @Test
    fun dpiOverride_bypassesAutoButNeverChangesSize() {
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(dpiOverride = 300), 1368, 3192
        )
        assertEquals(2560, w)
        assertEquals(1440, h)
        assertEquals(300, dpi)
    }

    @Test
    fun dpiOverride_beyondBoundsIsClampedTo480() {
        val (_, _, dpi) = VdDimensions.compute(
            defaultRequest(dpiOverride = 999), 1368, 3192
        )
        assertEquals(480, dpi)
    }

    @Test
    fun carSmallViewport_reportedDpiScalesWithVdScale() {
        // 实车现场（#20 复现 / #71 发烫）：车机 1204x660 @160dpi，手机真实 1368x3192。
        // 封顶前 VD 会放大 2.6512 倍到 3192x1748（每帧 5.58MP，实测持续发烫）；
        // issue #71 封顶 2560 → 2560x1402（-36% 合成像素），车机密度同步放大到 340，
        // 落出 120..320 带后由 golden 公式给出 302。
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(carW = 1204, carH = 660), 1368, 3192
        )
        assertEquals(2560, w)
        assertEquals(1402, h)
        assertEquals(302, dpi)
    }

    @Test
    fun upscaleIsCappedAtMaxUpscaledWidth() {
        // 手机物理长边远超上限时，VD 只到 [VdDimensions.MAX_UPSCALED_WIDTH]。
        // 代价：输入法按物理宽渲染，会在这里被裁掉 3200-2560 = 640px（约 20%），
        // 这是 issue #71 明确接受的取舍（发烫 vs 键盘）。
        val (w, _, _) = VdDimensions.compute(
            defaultRequest(carW = 1204, carH = 660), 1440, 3200
        )
        assertEquals(VdDimensions.MAX_UPSCALED_WIDTH, w)
    }

    @Test
    fun carDpiOutsideAutoBand_fallsBackToGoldenDpi() {
        // 480 放大后是 640，落出 [120,320] → golden = 1440*0.52*160/385 = 311，
        // maxSafe = 1440*80/360 = 320，所以 golden 值原样保留。
        val (_, _, dpi) = VdDimensions.compute(
            defaultRequest(carDpi = 480), 1368, 3192
        )
        assertEquals(311, dpi)
    }

    @Test
    fun imeWidthInvariant_holdsUpToTheUpscaleCap() {
        // anti-crop 的本意：VD 长边 >= 手机物理长边（IME 按物理宽渲染）。
        // issue #71 起放大封顶 2560，超过上限的机型必然裁掉一部分键盘 ——
        // 这里钉住「上限以内仍然满足」，超上限的行为由 upscaleIsCappedAtMaxUpscaledWidth 覆盖。
        val fixtures = listOf(
            Triple(1368, 3192, 3192L), // portrait phone, long edge 3192
            Triple(3192, 1368, 3192L), // landscape phone, long edge 3192
        )
        for ((pw, ph, longEdge) in fixtures) {
            val (w, _, _) = VdDimensions.compute(defaultRequest(), pw, ph)
            val required = minOf(longEdge, VdDimensions.MAX_UPSCALED_WIDTH.toLong())
            assertTrue(
                "VD width $w must be >= $required (phone ${pw}x$ph, cap ${VdDimensions.MAX_UPSCALED_WIDTH})",
                w >= required
            )
        }
    }
}
