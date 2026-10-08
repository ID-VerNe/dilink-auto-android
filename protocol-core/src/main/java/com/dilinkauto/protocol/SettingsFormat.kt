package com.dilinkauto.protocol

/**
 * Pure value ⇄ display helpers for the car settings UI (DPI / bitrate / FPS).
 *
 * These live in protocol-core — free of Android and Compose — because the car
 * UI cannot be exercised without BYD hardware, while the maths that decides
 * *what number gets stored* must be regression-tested on the JVM. The bitrate
 * label in particular used to be derived by integer division, so the 2.5 Mbps
 * preset rendered as "2M" — identical to the 2 Mbps preset (audit UX-06).
 */
object SettingsFormat {
    /** Auto / no-override sentinel for DPI, persisted as 0. */
    const val DPI_AUTO = 0

    /** The four bitrate presets the settings chips offer, in bps. */
    val BITRATE_PRESETS = listOf(2_000_000, 2_500_000, 3_000_000, 4_000_000, 6_000_000)

    /** DPI presets offered as chips. 0 = Auto. */
    val DPI_PRESETS = listOf(DPI_AUTO, 140, 160, 180, 200)

    /** Frame-rate presets offered as chips. */
    val FPS_PRESETS = listOf(20, 24, 30, 60)

    /**
     * Render a bitrate in bps as a compact Mbps label: `4_000_000` → `"4M"`,
     * `2_500_000` → `"2.5M"`, `3_333_000` → `"3.33M"`.
     *
     * Integer-only so the result is exact and locale-independent. Fractions are
     * rounded half-up to two decimals; the trailing zeros are dropped so a whole
     * value never renders as `"4.0M"`.
     *
     * @param bps bitrate in bits per second; expected non-negative
     */
    fun formatBitrateMbps(bps: Int): String {
        val whole = bps / 1_000_000
        val thousandths = (bps % 1_000_000) / 1_000          // 0..999
        if (thousandths == 0) return "${whole}M"

        val hundredths = (thousandths + 5) / 10              // round to 2 dp, 0..100
        return when {
            hundredths == 100 -> "${whole + 1}M"             // 1.999M -> "2M"
            hundredths % 10 == 0 -> "$whole.${hundredths / 10}M"
            hundredths < 10 -> "$whole.0${hundredths}M"
            else -> "$whole.${hundredths}M"
        }
    }

    /**
     * Digits to prefill a manual-entry field with, or `""` when [value] is one
     * of [presets].
     *
     * A selected chip is already the readout, so the field must stay empty in
     * that case; only a value with no chip of its own belongs in the field. The
     * field also accepts a different unit than the stored value ([divisor]:
     * bps → Mbps for the bitrate row).
     */
    fun manualSeed(value: Int, presets: List<Int>, divisor: Int = 1): String =
        if (value in presets) "" else (value / divisor).toString()

    /** Clamp a bitrate to the range the settings UI accepts. */
    fun coerceBitrate(bps: Int): Int = bps.coerceIn(VideoConfig.MIN_BITRATE, VideoConfig.MAX_BITRATE)

    /** Clamp a frame rate to the range the settings UI accepts. */
    fun coerceFps(fps: Int): Int = fps.coerceIn(VideoConfig.MIN_FPS, VideoConfig.MAX_FPS)

    /** Clamp a DPI override, delegating to the shared deploy-side rule (0 = Auto). */
    fun coerceDpi(dpi: Int): Int = VdDeployArgs.coerceDpiOverride(dpi)
}
