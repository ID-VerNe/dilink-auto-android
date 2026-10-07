package com.dilinkauto.desktop.config

import com.dilinkauto.protocol.VideoConfig
import java.io.File

/**
 * 桌面端持久化配置（`%APPDATA%\DiLinkAuto\config.json`）。
 *
 * 键名刻意沿用 Android 侧的 `SharedPreferences` 键（见车机 `CarConnectionService`
 * 与 `AppPrefs`），这样两端可以对照排查，也方便未来做配置同步。
 *
 * | 键 | 含义 | 桌面端用途 |
 * |---|---|---|
 * | `dev_phone_ip` | 手机 IP | 命令行没传 IP 时的默认值 |
 * | `dev_mode` | 开发者模式 | Phase 5c 的 ADB 部署路径开关 |
 * | `startup_dpi` | 强制 DPI（0=自动） | 握手时的 `dpiOverride` |
 * | `startup_fps` | 目标帧率 | 握手时的 `targetFps` |
 * | `startup_bitrate` | 目标码率 | 握手时的 `bitrate` |
 * | `startup_hwaccel` | 是否用 D3D11VA 硬解 | 解码管线构造参数（Phase 5b） |
 * | `keep_awake` | 会话期间是否阻止本机息屏 | `KeepAwake` 的默认开关（Phase 5d） |
 * | `log_enabled` | 是否写本地日志 | 控制 `desktop.log` |
 */
data class DesktopSettings(
    val devPhoneIp: String = "",
    val devMode: Boolean = false,
    val startupDpi: Int = 0,
    val startupFps: Int = VideoConfig.TARGET_FPS,
    val startupBitrate: Int = VideoConfig.DEFAULT_BITRATE,
    val logEnabled: Boolean = true,
    val startupHwaccel: Boolean = false,
    val keepAwake: Boolean = true,
) {

    fun toMap(): Map<String, Any?> = linkedMapOf(
        KEY_DEV_PHONE_IP to devPhoneIp,
        KEY_DEV_MODE to devMode,
        KEY_STARTUP_DPI to startupDpi,
        KEY_STARTUP_FPS to startupFps,
        KEY_STARTUP_BITRATE to startupBitrate,
        KEY_LOG_ENABLED to logEnabled,
        KEY_STARTUP_HWACCEL to startupHwaccel,
        KEY_KEEP_AWAKE to keepAwake,
    )

    fun toJson(): String = JsonConfig.write(toMap())

    /** 写回配置文件；失败时返回 false 并回报原因（配置写不进去不该让程序起不来）。 */
    fun save(file: File = DesktopPaths.configFile(), onError: (String) -> Unit = {}): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(toJson())
        }.onFailure { onError("写入 $file 失败: ${it.message}") }.isSuccess
    }

    companion object {
        const val KEY_DEV_PHONE_IP = "dev_phone_ip"
        const val KEY_DEV_MODE = "dev_mode"
        const val KEY_STARTUP_DPI = "startup_dpi"
        const val KEY_STARTUP_FPS = "startup_fps"
        const val KEY_STARTUP_BITRATE = "startup_bitrate"
        const val KEY_LOG_ENABLED = "log_enabled"
        const val KEY_STARTUP_HWACCEL = "startup_hwaccel"
        const val KEY_KEEP_AWAKE = "keep_awake"

        /** DPI 合法区间：与手机侧 `VideoConfig` 的可接受范围一致，越界直接夹紧。 */
        const val MAX_DPI = 640
        private val DPI_RANGE = 0..MAX_DPI
        private val FPS_RANGE = 5..60
        private val BITRATE_RANGE = 500_000..50_000_000

        /**
         * 从解析好的键值表构造配置。
         *
         * 类型不符的键一律退回默认值——手写的 config.json 很容易把 `"true"` 写成 `true`，
         * 与其崩在启动路径上，不如忽略这一项继续跑。
         */
        fun from(values: Map<String, Any?>): DesktopSettings {
            val defaults = DesktopSettings()
            return DesktopSettings(
                devPhoneIp = (values[KEY_DEV_PHONE_IP] as? String)?.trim().orEmpty(),
                devMode = (values[KEY_DEV_MODE] as? Boolean) ?: defaults.devMode,
                startupDpi = intValue(values[KEY_STARTUP_DPI], defaults.startupDpi).coerceIn(DPI_RANGE),
                startupFps = intValue(values[KEY_STARTUP_FPS], defaults.startupFps).coerceIn(FPS_RANGE),
                startupBitrate = intValue(values[KEY_STARTUP_BITRATE], defaults.startupBitrate)
                    .coerceIn(BITRATE_RANGE),
                logEnabled = (values[KEY_LOG_ENABLED] as? Boolean) ?: defaults.logEnabled,
                startupHwaccel = (values[KEY_STARTUP_HWACCEL] as? Boolean) ?: defaults.startupHwaccel,
                keepAwake = (values[KEY_KEEP_AWAKE] as? Boolean) ?: defaults.keepAwake,
            )
        }

        /** 读取配置；文件不存在或损坏时返回默认值，并通过 [onError] 汇报原因。 */
        fun load(
            file: File = DesktopPaths.configFile(),
            onError: (String) -> Unit = {},
        ): DesktopSettings {
            if (!file.isFile) return DesktopSettings()
            val text = runCatching { file.readText() }
                .getOrElse {
                    onError("读取 $file 失败: ${it.message}")
                    return DesktopSettings()
                }
            val values = runCatching { JsonConfig.parse(text) }
                .getOrElse {
                    onError("解析 $file 失败: ${it.message}")
                    return DesktopSettings()
                }
            return from(values)
        }

        private fun intValue(raw: Any?, fallback: Int): Int = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull() ?: fallback
            else -> fallback
        }
    }
}
