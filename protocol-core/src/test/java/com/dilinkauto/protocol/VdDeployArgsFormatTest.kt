package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VdDeployArgs.format / coerceDpiOverride 单测。
 * （sanitizeCarHost 与"默认 carHost='-'"已由 AuditProtocolFixesTest 覆盖，这里只补
 * 精确 9 字段布局、等比缩放钳制、bitrate 边界。）
 *
 * format 是 PipelineServer.main() 的 argv 契约，字段顺序/钳制一旦漂移，部署参数就错位。
 */
class VdDeployArgsFormatTest {

    @Test
    fun `format emits exact nine field layout`() {
        val out = VdDeployArgs.format(
            vdWidth = 1280, vdHeight = 720, dpi = 160, phoneHost = "127.0.0.1",
            encodeWidth = 1280, encodeHeight = 720, fps = 24,
            bitrate = 4_000_000, carHost = "10.0.0.7"
        )
        assertEquals("1280 720 160 127.0.0.1 1280 720 24 4000000 10.0.0.7", out)
        assertEquals(9, out.split(" ").size)
    }

    @Test
    fun `format clamps encoder dims preserving aspect ratio not to square`() {
        // 竖屏 1080x2152 必须等比缩到 1080 高度上限，而不是分开 clamp 成方形 1080x1080。
        val out = VdDeployArgs.format(
            vdWidth = 1080, vdHeight = 2152, dpi = 160, phoneHost = "127.0.0.1",
            encodeWidth = 1080, encodeHeight = 2152, fps = 24
        )
        val f = out.split(" ")
        val ew = f[4].toInt()
        val eh = f[5].toInt()
        assertFalse("高度应钳到 1080 上限", eh != 1080)
        assertTrue("宽应为偶数", ew % 2 == 0)
        assertTrue("高应为偶数", eh % 2 == 0)
        assertTrue("竖屏宽高比必须保留（不得退化成方形）：ew=$ew eh=$eh", ew < eh)
        assertFalse("不得是方形 1080 1080", out.contains(" 1080 1080 "))
    }

    @Test
    fun `format bitrate outside input range falls back to default`() {
        val base = { br: Int ->
            VdDeployArgs.format(1280, 720, 160, "127.0.0.1", 1280, 720, 24, bitrate = br)
                .split(" ")[7]
        }
        assertEquals("500000", base(500_000))          // INPUT_MIN_BITRATE 边界含内
        assertEquals("20000000", base(20_000_000))     // INPUT_MAX_BITRATE 边界含内
        assertEquals(VideoConfig.DEFAULT_BITRATE.toString(), base(499_999))   // 低于输入下限
        assertEquals(VideoConfig.DEFAULT_BITRATE.toString(), base(20_000_001)) // 高于输入上限
    }

    @Test
    fun `format zero encoder dims yields well formed string`() {
        // encodeWidth/Height=0 → scale 仍为 1（除以 0f 得 Inf，被 minOf 排除），不崩，EW/EH=0
        val out = VdDeployArgs.format(1280, 720, 160, "127.0.0.1", encodeWidth = 0, encodeHeight = 0, fps = 24)
        val f = out.split(" ")
        assertEquals(9, f.size)
        assertEquals("0", f[4])
        assertEquals("0", f[5])
    }

    @Test
    fun `coerce dpi override boundaries`() {
        assertEquals(0, VdDeployArgs.coerceDpiOverride(-1))    // <=0 → 0（Auto）
        assertEquals(0, VdDeployArgs.coerceDpiOverride(0))
        assertEquals(120, VdDeployArgs.coerceDpiOverride(1))   // < MIN → MIN
        assertEquals(120, VdDeployArgs.coerceDpiOverride(119))
        assertEquals(120, VdDeployArgs.coerceDpiOverride(120))
        assertEquals(480, VdDeployArgs.coerceDpiOverride(480))
        assertEquals(480, VdDeployArgs.coerceDpiOverride(481)) // > MAX → MAX
        assertEquals(480, VdDeployArgs.coerceDpiOverride(10_000))
    }
}
