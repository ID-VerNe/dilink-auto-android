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
        fps: Int,
        bitrate: Int = VideoConfig.DEFAULT_BITRATE
    ): String {
        // 等比缩放到编码上限内：此前宽高分开 clamp 会把竖屏 1080x2152 压成方形
        // 1080x1080，导致车机端画面比例失真；等比缩放保持车机 viewport 原始宽高比。
        val encodeScale = minOf(1f, MAX_ENCODE_WIDTH.toFloat() / encodeWidth, MAX_ENCODE_HEIGHT.toFloat() / encodeHeight)
        val ew = (encodeWidth * encodeScale).toInt() and 0x7FFFFFFE
        val eh = (encodeHeight * encodeScale).toInt() and 0x7FFFFFFE
        val br = if (bitrate in MIN_BITRATE..MAX_BITRATE) bitrate else VideoConfig.DEFAULT_BITRATE
        return "$vdWidth $vdHeight $dpi $phoneHost $ew $eh $fps $br"
    }

    const val MIN_BITRATE = 500_000
    const val MAX_BITRATE = 20_000_000

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

