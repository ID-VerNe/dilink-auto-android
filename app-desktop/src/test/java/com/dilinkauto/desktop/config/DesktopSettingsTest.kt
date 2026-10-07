package com.dilinkauto.desktop.config

import com.dilinkauto.protocol.VideoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [DesktopSettings] 单测：默认值、文件往返、容错与区间夹紧。 */
class DesktopSettingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun defaults_matchProtocolConstants() {
        val s = DesktopSettings()
        assertEquals("", s.devPhoneIp)
        assertFalse(s.devMode)
        assertEquals(0, s.startupDpi)
        assertEquals(VideoConfig.TARGET_FPS, s.startupFps)
        assertEquals(VideoConfig.DEFAULT_BITRATE, s.startupBitrate)
        assertTrue(s.logEnabled)
        assertFalse("硬解默认关（首次跑更稳妥）", s.startupHwaccel)
        assertTrue("默认保持本机常亮", s.keepAwake)
    }

    @Test
    fun save_thenLoad_roundTrips() {
        val file = temp.newFile("config.json")
        val original = DesktopSettings(
            devPhoneIp = "192.168.3.206",
            devMode = true,
            startupDpi = 240,
            startupFps = 30,
            startupBitrate = 8_000_000,
            logEnabled = false,
            startupHwaccel = true,
            keepAwake = false,
        )

        assertTrue(original.save(file))
        assertEquals(original, DesktopSettings.load(file))
    }

    @Test
    fun load_missingFile_returnsDefaults() {
        val file = temp.newFile("nope.json").apply { delete() }
        assertEquals(DesktopSettings(), DesktopSettings.load(file))
    }

    @Test
    fun load_corruptFile_reportsErrorAndReturnsDefaults() {
        val file = temp.newFile("config.json")
        file.writeText("{ this is not json")
        val errors = mutableListOf<String>()

        val settings = DesktopSettings.load(file) { errors.add(it) }

        assertEquals(DesktopSettings(), settings)
        assertTrue("应回报解析失败：$errors", errors.single().contains("解析"))
    }

    @Test
    fun load_readsTheDocumentedKeysFromHandwrittenJson() {
        // 手写（而非 save 产出）的配置文件也必须能读——用户会直接编辑它
        val file = temp.newFile("config.json")
        file.writeText(
            """
            {
              "dev_phone_ip": "192.168.3.206",
              "dev_mode": true,
              "startup_dpi": 320,
              "startup_fps": 25,
              "startup_bitrate": 6000000,
              "log_enabled": false
            }
            """.trimIndent()
        )

        val s = DesktopSettings.load(file)

        assertEquals("192.168.3.206", s.devPhoneIp)
        assertTrue(s.devMode)
        assertEquals(320, s.startupDpi)
        assertEquals(25, s.startupFps)
        assertEquals(6_000_000, s.startupBitrate)
        assertFalse(s.logEnabled)
    }

    @Test
    fun load_clampsOutOfRangeNumbers() {
        val s = DesktopSettings.from(
            mapOf(
                DesktopSettings.KEY_STARTUP_DPI to 9999L,
                DesktopSettings.KEY_STARTUP_FPS to 500L,
                DesktopSettings.KEY_STARTUP_BITRATE to 1L,
            )
        )
        assertEquals(640, s.startupDpi)
        assertEquals(60, s.startupFps)
        assertEquals(500_000, s.startupBitrate)
    }

    @Test
    fun load_wrongTypesFallBackToDefaults() {
        val s = DesktopSettings.from(
            mapOf(
                DesktopSettings.KEY_DEV_PHONE_IP to 12345L, // 不是字符串
                DesktopSettings.KEY_DEV_MODE to "true", // 字符串不是布尔
                DesktopSettings.KEY_LOG_ENABLED to "yes",
                DesktopSettings.KEY_STARTUP_FPS to "abc",
            )
        )
        val defaults = DesktopSettings()
        assertEquals(defaults.devPhoneIp, s.devPhoneIp)
        assertEquals(defaults.devMode, s.devMode)
        assertEquals(defaults.logEnabled, s.logEnabled)
        assertEquals(defaults.startupFps, s.startupFps)
    }

    @Test
    fun from_acceptsNumericStringsForNumbers() {
        // 手写配置里把数字写成 "240" 很常见，容忍它
        val s = DesktopSettings.from(mapOf(DesktopSettings.KEY_STARTUP_DPI to "240"))
        assertEquals(240, s.startupDpi)
    }

    @Test
    fun save_reportsErrorForUnwritablePath() {
        val dir = temp.newFolder("blocked")
        val file = java.io.File(dir, "sub/config.json")
        // 用一个文件占住父目录位置，让 mkdirs 失败
        java.io.File(dir, "sub").writeText("occupied")
        val errors = mutableListOf<String>()

        val ok = DesktopSettings().save(file) { errors.add(it) }

        assertFalse(ok)
        assertNotNull(errors.firstOrNull())
    }

    @Test
    fun toJson_containsAllDocumentedKeys() {
        val json = DesktopSettings().toJson()
        for (key in listOf(
            DesktopSettings.KEY_DEV_PHONE_IP,
            DesktopSettings.KEY_DEV_MODE,
            DesktopSettings.KEY_STARTUP_DPI,
            DesktopSettings.KEY_STARTUP_FPS,
            DesktopSettings.KEY_STARTUP_BITRATE,
            DesktopSettings.KEY_LOG_ENABLED,
            DesktopSettings.KEY_STARTUP_HWACCEL,
            DesktopSettings.KEY_KEEP_AWAKE,
        )) {
            assertTrue("缺少键 $key", json.contains("\"$key\""))
        }
    }

    @Test
    fun from_readsHardwareAccelAndKeepAwakeKeys() {
        val s = DesktopSettings.from(
            mapOf(
                DesktopSettings.KEY_STARTUP_HWACCEL to true,
                DesktopSettings.KEY_KEEP_AWAKE to false,
            )
        )
        assertTrue(s.startupHwaccel)
        assertFalse(s.keepAwake)
    }

    @Test
    fun from_wrongTypesForBooleanKeysFallBackToDefaults() {
        // 手写配置常把布尔写成字符串；类型不符必须退回默认而不是崩
        val s = DesktopSettings.from(
            mapOf(
                DesktopSettings.KEY_STARTUP_HWACCEL to "true",
                DesktopSettings.KEY_KEEP_AWAKE to "no",
            )
        )
        val defaults = DesktopSettings()
        assertEquals(defaults.startupHwaccel, s.startupHwaccel)
        assertEquals(defaults.keepAwake, s.keepAwake)
    }
}
