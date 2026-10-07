package com.dilinkauto.desktop

import com.dilinkauto.protocol.HandshakeRequest

/**
 * 由桌面配置构造发给手机的 [HandshakeRequest]。
 *
 * 车机端对应的是 `app-server` 的 `buildHandshakeRequest`；桌面端没有 PackageManager / Build.MODEL，
 * 设备名与版本号改由配置提供。视口尺寸做偶数对齐（H.264 编码器要求偶数边长）。
 */
object HandshakeFactory {

    fun build(config: DesktopConfig): HandshakeRequest = HandshakeRequest(
        deviceName = config.deviceName,
        screenWidth = evenAlign(config.viewportWidth),
        screenHeight = evenAlign(config.viewportHeight),
        screenDpi = config.screenDpi,
        appVersionCode = config.appVersionCode,
        targetFps = config.targetFps,
        dpiOverride = config.dpiOverride,
        bitrate = config.bitrate,
    )

    /** 向下取偶数，并保证最小为 2（奇数边长会被 H.264 编码器拒绝或导致画面错位）。 */
    fun evenAlign(value: Int): Int {
        val floored = if (value % 2 == 0) value else value - 1
        return floored.coerceAtLeast(2)
    }
}