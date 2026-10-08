package com.dilinkauto.server.service

import android.content.Context
import android.os.Build
import com.dilinkauto.protocol.HandshakeRequest

/**
 * Builds the car's [HandshakeRequest] to the phone.
 *
 * The same fields are populated at two call sites — initial connect and
 * mid-stream rotation re-handshake — so the package-version lookup and
 * device-name formatting live here once. Only the viewport dims and DPI differ
 * between the two sites; callers compute those from the current display state.
 *
 * Extracted from [CarConnectionService] to remove duplication between
 * `connectToPhone` and `onCarViewportChanged`.
 */
internal fun buildHandshakeRequest(
    context: Context,
    screenWidth: Int,
    screenHeight: Int,
    screenDpi: Int,
    targetFps: Int,
    dpiOverride: Int,
    bitrate: Int = 0
): HandshakeRequest {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    // Dimensions arrive pre-aligned from the caller (CarViewport.size), because
    // on this side the nav bar makes the correct direction "widen the bar" —
    // the opposite of the desktop's floor. Aligning again here would undo it.
    return HandshakeRequest.builder()
        .deviceName("DiLink-${Build.MODEL}")
        .screenSize(screenWidth, screenHeight)
        .screenDpi(screenDpi)
        .appVersionCode(@Suppress("DEPRECATION") pi.versionCode)
        .targetFps(targetFps)
        .appVersionName(pi.versionName ?: "")
        .dpiOverride(dpiOverride)
        .bitrate(bitrate)
        .build()
}
