package com.dilinkauto.protocol

/**
 * Pure viewport math for the phone-side VirtualDisplay created in response to
 * a car [HandshakeRequest].
 *
 * Lives in protocol-core (not app-client) so the DPI/size rules can be read
 * and tested as plain JVM code — the caller passes the phone's **real
 * physical** pixel size; no Android types are involved here.
 *
 * Rules:
 *  - VD width/height start from the car viewport, even-aligned (H.264 needs
 *    even dimensions) *before* scaling.
 *  - Anti-crop scale: if the car viewport is narrower than the phone's
 *    physical width (orientation-resolved: a portrait phone's long edge acts
 *    as the width for a landscape car), scale the VD up to match. Many
 *    Chinese ROMs hardcode IME width to the **physical display width**, and
 *    the IME width does not follow VD density — a narrower VD chops the
 *    keyboard horizontally no matter what DPI is negotiated. Scaling
 *    preserves the car's aspect ratio while satisfying the OS width
 *    requirement.
 *  - **The phone size must come from real physical metrics
 *    (`WindowManager.maximumWindowMetrics` / `Display.getRealMetrics`), NOT
 *    `resources.displayMetrics`.** In screen-compat mode the latter reports
 *    compat-scaled values — the 2026-10-09 test phone (real 1368x3192
 *    @560dpi) reports a 2992px long edge, so the VD came out 2992 wide while
 *    the IME rendered at the true 3192, overhanging the VD by 200px (~6.7%)
 *    identically at DPI 120/200/300/400.
 *  - DPI: a car-side override (coerced to [120, 480]) bypasses the
 *    portrait-app-safe cap; otherwise auto-calibrate for a ~380dp logical
 *    width in landscape.
 */
object VdDimensions {

    /**
     * @param phoneRealWidth  real physical pixel width of the phone's default
     *   display (compat-scaling must be bypassed by the caller — see class
     *   KDoc).
     * @param phoneRealHeight real physical pixel height of the phone's
     *   default display.
     * @return (vdWidth, vdHeight, dpi) for the VD deploy args and the
     *   VirtualDisplay creation.
     */
    fun compute(
        request: HandshakeRequest,
        phoneRealWidth: Int,
        phoneRealHeight: Int,
    ): Triple<Int, Int, Int> {
        var vdWidth = DimAlign.even(request.screenWidth)
        var vdHeight = DimAlign.even(request.screenHeight)

        val isCarLandscape = vdWidth > vdHeight
        val isPhoneLandscape = phoneRealWidth > phoneRealHeight
        val phonePhysicalWidth =
            if (isPhoneLandscape == isCarLandscape) phoneRealWidth else phoneRealHeight
        if (vdWidth < phonePhysicalWidth) {
            val scale = phonePhysicalWidth.toFloat() / vdWidth
            vdWidth = DimAlign.even((vdWidth * scale).toInt())
            vdHeight = DimAlign.even((vdHeight * scale).toInt())
        }

        val dpi = if (request.dpiOverride > 0) {
            VdDeployArgs.coerceDpiOverride(request.dpiOverride)
        } else {
            VideoConfig.calculateOptimalDpi(vdWidth, vdHeight, request.screenDpi)
        }
        return Triple(vdWidth, vdHeight, dpi)
    }
}
