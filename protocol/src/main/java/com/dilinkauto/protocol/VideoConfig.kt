package com.dilinkauto.protocol

/**
 * Shared video pipeline configuration.
 * All waits/polls on the video path should use FRAME_INTERVAL_MS as their max timeout.
 */
object VideoConfig {
    const val TARGET_FPS = 30  // phone doesn't overheat at this rate
    const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS  // 16ms at 60fps
    const val VIRTUAL_DISPLAY_DPI = 480  // legacy fallback constant
    const val TARGET_SW_DP = 600  // smallest-width dp for VD size calculation
    const val DEFAULT_FALLBACK_DPI = 160

    /**
     * Calculate optimal Virtual Display DPI for car display.
     *
     * Problem addressed:
     * When car display is in landscape (W > H, e.g. 2074x1020, 1408x792, 1280x720):
     * Portrait-only apps (like Amap / 高德地图, WeChat) are letterboxed by Android WMS.
     * Their window height equals VD height H, and window width is clamped by aspect ratio:
     * W_portrait ≈ H * (9 / 18) to H * (9 / 16) ≈ H * 0.5 to H * 0.5625.
     *
     * In Android design guidelines, handheld phone apps REQUIRE a minimum logical width:
     * sw >= 360dp (ideal comfortable phone width is 380dp ~ 412dp).
     *
     * If DPI is set too high (e.g. 480 or 560):
     * W_dp = W_portrait / (DPI / 160) = (H * 0.5 * 160) / DPI.
     * At H=792, DPI=480: W_dp = 132dp!
     * At H=1020, DPI=560: W_dp = 145dp!
     * All UI elements (icons, text, tabs) keep their huge 480dpi pixel size and get crushed
     * into a 140dp sliver, overlapping each other and making buttons unclickable.
     *
     * To achieve an "iPad-like phone app experience" where the app renders at standard
     * comfortable touch sizes:
     * We require W_dp >= 360dp (targeting ~380dp ~ 400dp).
     *
     * Therefore:
     * DPI <= (H * 0.5 * 160) / 360 = (H * 80) / 360 = H * 2 / 9 ≈ H / 4.5.
     *
     * For landscape (W >= H):
     * - H=1080/1020: max DPI ≈ 226 -> optimal DPI ≈ 200~220
     * - H=792/720: max DPI ≈ 160~176 -> optimal DPI ≈ 160
     * - H=600: max DPI ≈ 133 -> optimal DPI ≈ 120~130
     *
     * For portrait (W < H, rotatable car screen rotated to vertical):
     * The entire width W is available.
     * Optimal DPI = W * 160 / 380. (e.g. W=892 -> DPI ≈ 375).
     */
    fun calculateOptimalDpi(width: Int, height: Int, carReportedDpi: Int = 0): Int {
        if (width <= 0 || height <= 0) return DEFAULT_FALLBACK_DPI

        val isLandscape = width >= height
        val maxSafeDpi = if (isLandscape) {
            ((height * 80f) / 360f).toInt()
        } else {
            ((width * 160f) / 380f).toInt()
        }

        val goldenDpi = if (isLandscape) {
            ((height * 0.52f * 160f) / 385f).toInt()
        } else {
            ((width * 160f) / 385f).toInt()
        }

        val baseDpi = if (carReportedDpi in 120..320) {
            minOf(carReportedDpi, maxSafeDpi)
        } else {
            goldenDpi
        }

        return baseDpi.coerceIn(120, maxOf(120, maxSafeDpi))
    }
}
