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
        // The 2026-10-09 test phone: real 1368x3192 @560dpi (portrait).
        // Compat-scaled metrics report 2992 for the long edge — this test
        // pins that 3192 (the true width the IME renders at) wins.
        val (w, h, dpi) = VdDimensions.compute(defaultRequest(), 1368, 3192)
        assertEquals(3192, w)
        assertEquals(1794, h) // 1080 * 3192/1920 = 1795.5 -> even-round down
        assertEquals(160, dpi) // car-reported 160 is in [120,320], below maxSafe
    }

    @Test
    fun landscapePhone_usesLongEdgeAsPhysicalWidthToo() {
        val (w, h, dpi) = VdDimensions.compute(defaultRequest(), 3192, 1368)
        assertEquals(3192, w)
        assertEquals(1794, h)
        assertEquals(160, dpi)
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
        assertEquals(160, dpi)
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
        // 1921x1081 evens to 1920x1080 first, then follows the standard path.
        val (w, h, _) = VdDimensions.compute(
            defaultRequest(carW = 1921, carH = 1081), 1368, 3192
        )
        assertEquals(3192, w)
        assertEquals(1794, h)
    }

    @Test
    fun dpiOverride_bypassesAutoButNeverChangesSize() {
        val (w, h, dpi) = VdDimensions.compute(
            defaultRequest(dpiOverride = 300), 1368, 3192
        )
        assertEquals(3192, w)
        assertEquals(1794, h)
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
    fun carDpiOutsideAutoBand_fallsBackToGoldenDpi() {
        // 480 is outside [120,320] -> golden = 1794*0.52*160/385 = 387,
        // below maxSafe (1794*80/360 = 398), so it survives the final clamp.
        val (_, _, dpi) = VdDimensions.compute(
            defaultRequest(carDpi = 480), 1368, 3192
        )
        assertEquals(387, dpi)
    }

    @Test
    fun imeFullWidthInvariant_holdsForBothOrientations() {
        // The whole point of the anti-crop scale: the VD's long edge must be
        // >= the phone's physical long edge whenever the car viewport is
        // narrower, because the IME renders at the physical width.
        val fixtures = listOf(
            Triple(1368, 3192, 3192L), // portrait phone, long edge 3192
            Triple(3192, 1368, 3192L), // landscape phone, long edge 3192
        )
        for ((pw, ph, longEdge) in fixtures) {
            val (w, _, _) = VdDimensions.compute(defaultRequest(), pw, ph)
            assertTrue(
                "VD width $w must be >= physical long edge $longEdge (phone ${pw}x$ph)",
                w >= longEdge
            )
        }
    }
}
