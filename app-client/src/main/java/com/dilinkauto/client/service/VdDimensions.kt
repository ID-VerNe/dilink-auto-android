package com.dilinkauto.client.service

import android.util.DisplayMetrics
import com.dilinkauto.protocol.DimAlign
import com.dilinkauto.protocol.HandshakeRequest
import com.dilinkauto.protocol.VdDeployArgs
import com.dilinkauto.protocol.VideoConfig

/**
 * Pure viewport math for the phone-side VirtualDisplay created in response to
 * a car [HandshakeRequest].
 *
 * Extracted from [ConnectionService.handleHandshake] so the DPI/size rules can
 * be read and tested without the rest of the handshake's side effects. The
 * handshake body orchestrates connection lifecycle, version checks, and VD
 * deploy dispatch; only the dimension/DPI computation lives here.
 *
 * Rules:
 *  - VD width/height start from the car viewport, even-aligned (H.264 needs
 *    even dimensions).
 *  - Anti-crop scale: if the car viewport is narrower than the phone's
 *    physical width, scale the VD up to match. Many Chinese ROMs (Meizu,
 *    Xiaomi) hardcode IME width to the physical display width; a narrower VD
 *    chops the keyboard horizontally. Scaling preserves the car's aspect
 *    ratio while satisfying the OS width requirement.
 *  - DPI: a car-side override (coerced to [120, 480]) bypasses the
 *    portrait-app-safe cap; otherwise auto-calibrate for a ~380dp logical
 *    width in landscape.
 */
internal object VdDimensions {

    /**
     * @return (vdWidth, vdHeight, dpi) for the VD deploy args and the
     *   VirtualDisplay creation.
     */
    fun compute(request: HandshakeRequest, dm: DisplayMetrics): Triple<Int, Int, Int> {
        var vdWidth = DimAlign.even(request.screenWidth)
        var vdHeight = DimAlign.even(request.screenHeight)

        val isCarLandscape = vdWidth > vdHeight
        val isPhoneLandscape = dm.widthPixels > dm.heightPixels
        val phonePhysicalWidth = if (isPhoneLandscape == isCarLandscape) dm.widthPixels else dm.heightPixels
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
