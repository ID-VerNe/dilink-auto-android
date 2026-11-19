package com.dilinkauto.server.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁住车机 ADB 部署路径的 DPI 策略（issue #20）。
 *
 * 背景：手机端 auto 分支已按手机视口算出 `1926x1054@234dpi`，但车机部署路径实际
 * 用**车机自身视口**（如 `1204x660`）建 VD。`234dpi` 在 `1204x660` 下高于
 * `VideoConfig.maxSafeDpi(1204, 660) = 146`，竖屏应用逻辑宽只剩约 234dp（<360dp），
 * 所以部署端必须按**最终 VD 尺寸**夹紧握手带来的 DPI。
 */
class VdDeployerDpiPolicyTest {

    private data class Case(
        val handshakeVdDpi: Int,
        val vdWidth: Int,
        val vdHeight: Int,
        val carReportedDpi: Int,
        val expectedDpi: Int,
        val expectedSource: String
    )

    private val cases = listOf(
        // 横屏 1204x660 安全上限 146：握手的 234 越界 → 夹紧到 146。
        Case(234, 1204, 660, 160, 146, "handshake-clamp-234-146"),
        // 恰好等于安全上限 → 原样使用，不算夹紧。
        Case(146, 1204, 660, 160, 146, "handshake"),
        // 无握手值 → 自动标定：车机上报 160 被安全上限夹到 146。
        Case(0, 1204, 660, 160, 146, "auto-146"),
        // 竖屏 660x1204 安全上限 277：234 未越界 → 原样使用。
        Case(234, 660, 1204, 160, 234, "handshake"),
        // 竖屏 660x1204 安全上限 277：300 越界 → 夹紧到 277。
        Case(300, 660, 1204, 160, 277, "handshake-clamp-300-277")
    )

    @Test
    fun deployDpiMatchesTable() {
        for (case in cases) {
            val actual = deployDpi(
                handshakeVdDpi = case.handshakeVdDpi,
                vdWidth = case.vdWidth,
                vdHeight = case.vdHeight,
                carReportedDpi = case.carReportedDpi
            )
            val label = "handshake=${case.handshakeVdDpi} vd=${case.vdWidth}x${case.vdHeight}"
            assertEquals("$label dpi", case.expectedDpi, actual.dpi)
            assertEquals("$label source", case.expectedSource, actual.source)
        }
    }
}
