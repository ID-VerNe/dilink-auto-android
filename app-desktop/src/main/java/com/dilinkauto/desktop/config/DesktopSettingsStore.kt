package com.dilinkauto.desktop.config

import java.io.File

/**
 * `config.json` 的读写（audit R3-SRP-16）。
 *
 * 文件 IO 从 [DesktopSettings] 数据类里分出去：数据类只保留字段、键名与
 * 解析/序列化（[DesktopSettings.from] / [DesktopSettings.toJson]），
 * 本类负责"从哪个文件读、写到哪个文件、失败怎么汇报"。
 */
class DesktopSettingsStore(private val file: File = DesktopPaths.configFile()) {

    /** 读取配置；文件不存在或损坏时返回默认值，并通过 [onError] 汇报原因。 */
    fun load(onError: (String) -> Unit = {}): DesktopSettings {
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
        return DesktopSettings.from(values)
    }

    /** 写回配置文件；失败时返回 false 并回报原因（配置写不进去不该让程序起不来）。 */
    fun save(settings: DesktopSettings, onError: (String) -> Unit = {}): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(settings.toJson())
        }.onFailure { onError("写入 $file 失败: ${it.message}") }.isSuccess
    }
}
