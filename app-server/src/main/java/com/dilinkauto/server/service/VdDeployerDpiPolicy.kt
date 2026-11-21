package com.dilinkauto.server.service

import com.dilinkauto.protocol.VideoConfig

/** 车机 ADB 部署路径最终生效的 DPI 及其来源令牌（issue #20）。 */
internal data class VdDeployDpi(val dpi: Int, val source: String)

/**
 * 按**最终 VD 尺寸**决定车机 ADB 部署用的 DPI（issue #20）。
 *
 * 背景：手机端 auto 分支按**手机**视口算出 `1926x1054@234dpi`，而车机部署路径实际用
 * **车机自身视口**（如 `1204x660`）建 VD。握手带来的 `234dpi` 在 `1204x660` 下高于
 * [VideoConfig.maxSafeDpi]（= 146），竖屏应用逻辑宽只剩约 234dp（< 360dp），
 * 图标/按钮会互相重叠到不可点。
 *
 * 因此这里必须用最终 VD 尺寸对握手 DPI 做一次夹紧：
 *  - [handshakeVdDpi] `> 0`：取 `min(handshakeVdDpi, maxSafeDpi(vdWidth, vdHeight))`；
 *    未夹紧来源令牌 `"handshake"`，夹紧 `"handshake-clamp-<原始>-<生效>"`
 *    （只含 `[A-Za-z0-9._-]`，可直接进 shell argv）。
 *  - 否则回落到 [VideoConfig.calculateOptimalDpi]，来源令牌 `"auto-<dpi>"`。
 *
 * @param handshakeVdDpi 握手响应带来的手机侧 DPI，`<= 0` = 未提供。
 * @param vdWidth 最终 VirtualDisplay 宽度（车机视口，偶数）。
 * @param vdHeight 最终 VirtualDisplay 高度（车机视口，偶数）。
 * @param carReportedDpi 车机自己上报的 densityDpi，仅供自动标定参考。
 */
internal fun deployDpi(
    handshakeVdDpi: Int,
    vdWidth: Int,
    vdHeight: Int,
    carReportedDpi: Int,
): VdDeployDpi {
    if (handshakeVdDpi > 0) {
        val safeMax = VideoConfig.maxSafeDpi(vdWidth, vdHeight)
        if (handshakeVdDpi > safeMax) {
            return VdDeployDpi(safeMax, "handshake-clamp-$handshakeVdDpi-$safeMax")
        }
        return VdDeployDpi(handshakeVdDpi, "handshake")
    }
    val dpi = VideoConfig.calculateOptimalDpi(vdWidth, vdHeight, carReportedDpi)
    return VdDeployDpi(dpi, "auto-$dpi")
}
