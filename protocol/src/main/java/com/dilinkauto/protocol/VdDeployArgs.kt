package com.dilinkauto.protocol

/**
 * Builds the argv tail for the vd-server ([com.dilinkauto.vdserver.PipelineServer])
 * process, shared by the three deploy sites (phone Shizuku, car USB ADB, car TCP ADB).
 *
 * PipelineServer.main() parses: `W H DPI PHONE_HOST EW EH FPS`
 *   - W H       — VirtualDisplay dims (phone-side: scaled up to prevent IME crop on
 *                 Chinese ROMs; car-side: native car viewport).
 *   - DPI       — VD density (auto-calibrated or car-side override).
 *   - PHONE_HOST — host the VD server reverse-connects to on [LIFECYCLE_PORT].
 *   - EW EH     — encoder dims, clamped to 1920x1080. The Snapdragon 439 VPU caps
 *                 hardware AVC decode at 1080p; encoding larger forces software
 *                 decode on the car's 8x A53 (single-digit fps). Phone-side these
 *                 are the car-native dims (1:1 with car pixels, no decode downscale);
 *                 car-side they equal the VD dims (already <= 1080p).
 *   - FPS       — target frame rate; all pipeline timeouts derive from it.
 */
object VdDeployArgs {
    const val MAX_ENCODE_WIDTH = 1920
    const val MAX_ENCODE_HEIGHT = 1080

    /** Valid range for a car-side DPI override (sent as HandshakeRequest.dpiOverride). */
    const val DPI_OVERRIDE_MIN = 120
    const val DPI_OVERRIDE_MAX = 480

    fun format(
        vdWidth: Int,
        vdHeight: Int,
        dpi: Int,
        phoneHost: String,
        encodeWidth: Int,
        encodeHeight: Int,
        fps: Int
    ): String {
        val ew = minOf(encodeWidth, MAX_ENCODE_WIDTH)
        val eh = minOf(encodeHeight, MAX_ENCODE_HEIGHT)
        return "$vdWidth $vdHeight $dpi $phoneHost $ew $eh $fps"
    }

    /**
     * Coerce a raw DPI override to the valid range, with 0 = Auto (no override).
     * Used by both the phone (HandshakeResponse echo) and the car UI's input
     * field so the two sides agree on what counts as a valid override.
     */
    fun coerceDpiOverride(value: Int): Int = when {
        value <= 0 -> 0
        value < DPI_OVERRIDE_MIN -> DPI_OVERRIDE_MIN
        value > DPI_OVERRIDE_MAX -> DPI_OVERRIDE_MAX
        else -> value
    }
}

