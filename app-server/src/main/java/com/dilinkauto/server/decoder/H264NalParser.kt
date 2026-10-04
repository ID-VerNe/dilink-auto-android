package com.dilinkauto.server.decoder

/**
 * H.264 NAL-unit parsing for the decoder path.
 *
 * Extracted from [VideoDecoder] (DRY/SRP audit S11) so the bitstream scan logic
 * is isolated from MediaCodec lifecycle and flush state. Pure byte-array in,
 * boolean out — no Android dependencies, unit-testable.
 */
object H264NalParser {

    /**
     * True if [data] contains an IDR picture (NAL unit type 5).
     *
     * Scans only the first ~1KB: NAL headers near the start of the access unit
     * determine frame type, and a full-buffer scan on every P-frame is wasteful
     * on a weak car CPU. Handles both 3-byte (`00 00 01`) and 4-byte
     * (`00 00 00 01`) start-code prefixes per the H.264 spec (Annex B).
     */
    fun isKeyFrame(data: ByteArray): Boolean {
        val limit = minOf(data.size - 4, 1024)
        var i = 0
        while (i < limit) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val nalStart = if (data[i + 2] == 1.toByte()) i + 3
                    else if (data[i + 2] == 0.toByte() && i + 3 < data.size && data[i + 3] == 1.toByte()) i + 4
                    else { i++; continue }
                if (nalStart < data.size) {
                    if ((data[nalStart].toInt() and 0x1F) == 5) return true
                }
            }
            i++
        }
        return false
    }
}
