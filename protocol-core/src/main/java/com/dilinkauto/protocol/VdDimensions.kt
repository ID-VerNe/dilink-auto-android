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
 *    width in landscape. **The car-reported density is scaled by the same
 *    anti-crop factor as the viewport** — the car reports density for its own
 *    (smaller) viewport, so using it verbatim on the enlarged VD makes the UI
 *    render too small (issue #20).
 */
object VdDimensions {

    /**
     * 放大上限（VD 宽度，px）。
     *
     * issue #71：anti-crop 放大的目的只有一个 —— 满足「输入法按物理宽渲染」的
     * ROM 硬编码。但 VD 每帧都要整屏合成，本机（车机 1204x660、手机物理宽 3192）
     * 被放大 2.65× 到 3192x1748，即每帧 5.58MP，是长时间导航持续发烫的主要来源。
     * 封顶 2560 后同场景降到 2560x1402（合成像素 -36%）。
     *
     * 代价（明确记录）：物理宽超过 2560 的机型，输入法仍会被裁掉一部分
     * （3192 → 2560 约 20%）。这是用发热换来的取舍，见 issue #71 的 plan 追评。
     */
    const val MAX_UPSCALED_WIDTH = 2560

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
        // 放大倍数要保留到 DPI 计算：车机上报的密度是按它自己的视口算的，
        // VD 放大多少倍，同一逻辑密度就要跟着放大多少倍（#20）。
        var scale = 1f
        // issue #71：放大目标同时受 [MAX_UPSCALED_WIDTH] 约束（发烫）。
        val targetWidth = minOf(phonePhysicalWidth, MAX_UPSCALED_WIDTH)
        if (vdWidth < targetWidth) {
            val sourceWidth = vdWidth
            scale = targetWidth.toFloat() / sourceWidth
            // 宽度直接取目标值，高度用整数比例算 —— 避免 `width * (target/width)`
            // 的浮点回算误差（1080*3192/1920 = 1795.5，float 可能落到 1794/1796 两侧）。
            vdWidth = DimAlign.even(targetWidth)
            vdHeight = DimAlign.even(((vdHeight.toLong() * targetWidth) / sourceWidth).toInt())
        }

        val dpi = if (request.dpiOverride > 0) {
            VdDeployArgs.coerceDpiOverride(request.dpiOverride)
        } else {
            // 未放大时 scale = 1，行为与原来完全一致；放大后把车机上报密度
            // 一起放大，否则 120..320 区间内会被原样保留，导致 UI 相对 VD 过小。
            VideoConfig.calculateOptimalDpi(
                vdWidth,
                vdHeight,
                (request.screenDpi * scale).toInt(),
            )
        }
        return Triple(vdWidth, vdHeight, dpi)
    }
}
