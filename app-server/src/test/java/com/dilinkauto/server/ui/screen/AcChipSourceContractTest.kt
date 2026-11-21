package com.dilinkauto.server.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #278-PA 的源码级回归钉子：信息栏空调 chip + 弹出设置块（温度档）。
 *
 * app-server 没有 Compose UI 测试依赖，沿用 [CarShellAltitudeChipTest] 的
 * 源码文本断言范式，钉住三条验收线：
 *  1. 空调 chip 必须存在且可点（否则回到「投屏时无法调空调」的现状）；
 *  2. 既有三 chip（时间/天气/电量）的非交互渲染路径必须保持（issue #39 验收标准）；
 *  3. 写链路必须走反射 + wrapper + 写后回读确认（#277 §2 / #188），
 *     写失败必须可被 UI 感知（issue #278 UX 表 S4：写失败静默）。
 */
class AcChipSourceContractTest {

    private val shellSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/ui/screen/CarShell.kt")
    private val infoBarSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/ui/InfoBar.kt")
    private val serviceSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/service/CarConnectionService.kt")
    private val providerSrc = sourceOf("app-server/src/main/java/com/dilinkauto/server/service/AcSettingsProvider.kt")

    @Test
    fun `InfoChip carries optional onClick without changing plain chips`() {
        assertTrue(
            "InfoChip 必须扩展 onClick 可选参数（issue #278：首次引入可点 chip）",
            infoBarSrc.contains("onClick: (() -> Unit)? = null"),
        )
        assertTrue(
            "onClick 为 null 的 chip 必须走原有纯文本渲染分支（issue #39 非交互约束对老 chip 继续成立）",
            infoBarSrc.contains("chip.onClick == null"),
        )
        assertFalse(
            "可点样式必须挂在 onClick 分支内，不得无条件套在所有 chip 上（老 chip 形态不能变；import 行不算）",
            infoBarSrc.lines()
                .filter { it.contains(".clickable") }
                .filterNot { it.trim().startsWith("import") }
                .any { !it.contains("onClick") },
        )
    }

    @Test
    fun `CarShell wires ac chip and ac control sheet`() {
        assertTrue(
            "CarShell 必须按 acState 生成空调 chip（无数据时不显示，不留死入口）",
            shellSrc.contains("AcControlState.chipLabel"),
        )
        assertTrue(
            "CarShell 必须渲染弹出设置块 AcControlSheet（PA 温度档）",
            shellSrc.contains("AcControlSheet("),
        )
        assertTrue(
            "CarShell 必须把温度步进接给 service（adjustAcTemperature）",
            shellSrc.contains("adjustAcTemperature"),
        )
    }

    @Test
    fun `service assembles AcSettingsProvider and exposes ac state`() {
        assertTrue(
            "CarConnectionService 必须装配 AcSettingsProvider（onCreate）",
            serviceSrc.contains("AcSettingsProvider("),
        )
        assertTrue(
            "CarConnectionService 必须暴露 acState 出口供 UI collectAsState",
            serviceSrc.contains("val acState"),
        )
    }

    @Test
    fun `AcSettingsProvider keeps the write link under contract`() {
        assertTrue(
            "写链路必须反射调用 setAcTemperature（#277 §2 矩阵）",
            providerSrc.contains("setAcTemperature"),
        )
        assertTrue(
            "写后必须回读 getTemprature 确认（AbsBYDAutoAcListener 是抽象类，反射造不出，回读替代）",
            providerSrc.contains("getTemprature"),
        )
        assertTrue(
            "写链路必须经 BydPermissionContext wrapper（#188）",
            providerSrc.contains("BydPermissionContext"),
        )
        assertTrue(
            "写失败必须有出口让 UI 感知（S4：写失败静默是最严重失效模式）",
            providerSrc.contains("onWriteFailed"),
        )
        assertTrue(
            "设备类名必须与 SDK 一致（android.hardware.bydauto.ac.BYDAutoAcDevice）",
            providerSrc.contains("android.hardware.bydauto.ac.BYDAutoAcDevice"),
        )
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录（同 CarShellAltitudeChipTest）。 */
    private fun sourceOf(relativePath: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("找不到 $relativePath —— 测试工作目录：${File(".").absolutePath}")
    }
}
