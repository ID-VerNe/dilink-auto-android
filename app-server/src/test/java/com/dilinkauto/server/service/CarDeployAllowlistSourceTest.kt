package com.dilinkauto.server.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #21 的源码级钉子：**车机端 ADB 部署路径**必须把「握手响应带来的车机可见应用
 * 白名单」交到 vd-server 的 argv（`ALLOWED` 槽位）。
 *
 * 缺陷形态（2026-11-19 实车）：引擎侧过滤早已写好（`CarCommandRouter` →
 * `RecentsParser.pickQuickSwitchTarget`），但车机部署时 `VdServerDeployer.buildPlan`
 * 不传 `allowedPackages`，引擎日志是 `Allowlist: not handed down (no recents filtering)`，
 * 建屏恢复把白名单外的 Shizuku 管理器 / 淘宝拉进 VD，隐私应用被投到车机屏。
 *
 * 白名单**不能**取车机的 APP_LIST：车机在收到握手响应的那一刻就部署引擎
 * （`CarSessionCoordinator.handleControlFrame`），而 APP_LIST 要等 VD 生命周期连上
 * （引擎已启动）才由手机发出 —— 等它只会死锁。所以数据通路只能是握手响应尾字段。
 *
 * 只读源码文本，不依赖 Android 运行时（沿用 `SyncFrameRequestGateSourceTest` 的范式）。
 */
class CarDeployAllowlistSourceTest {

    private val deployerPath = "app-server/src/main/java/com/dilinkauto/server/service/VdServerDeployer.kt"
    private val coordinatorPath = "app-server/src/main/java/com/dilinkauto/server/service/CarSessionCoordinator.kt"
    private val clientPath = "app-client/src/main/java/com/dilinkauto/client/service/ConnectionService.kt"
    private val messagesPath = "protocol-core/src/main/java/com/dilinkauto/protocol/Messages.kt"

    private val deployer = sourceOf(deployerPath)
    private val coordinator = sourceOf(coordinatorPath)
    private val client = sourceOf(clientPath)
    private val messages = sourceOf(messagesPath)

    @Test
    fun `handshake response carries the car visible allowlist`() {
        assertTrue(
            "HandshakeResponse 必须带「车机可见应用」尾字段，否则车机部署引擎拿不到白名单" +
                "（issue #21：引擎日志 Allowlist: not handed down）",
            messages.contains("val allowedPackages: Set<String>"),
        )
        assertTrue(
            "握手响应必须用 VdDeployArgs.formatAllowedPackages 编码白名单（与 argv 同一套校验/哨兵）",
            messages.contains("VdDeployArgs.formatAllowedPackages("),
        )
    }

    @Test
    fun `phone hands the allowlist down with the handshake response`() {
        // 只看 `val resp = HandshakeResponse(` 那一段实参列表：Shizuku 部署路径里也有
        // 一处同名局部变量，整文件 contains 会把缺口误判成已接线。
        val responseArgs = client
            .substringAfter("val resp = HandshakeResponse(")
            .substringBefore("\n        )")
        assertTrue(
            "手机端构造 HandshakeResponse 时必须带上 AppListBuilder 的白名单" +
                "（与 Shizuku 部署路径同一份来源，issue #21）",
            responseArgs.contains("allowedPackages ="),
        )
        assertTrue(
            "握手响应用的白名单必须来自 AppListBuilder.allowedPackages（与 Shizuku 部署路径同源）",
            client.contains("val allowedPackages = appListBuilder.allowedPackages().orEmpty()"),
        )
    }

    @Test
    fun `car stores the allowlist from the handshake response`() {
        assertTrue(
            "车机端必须把握手响应里的白名单落到会话状态（handshakeAllowedPackages），" +
                "否则部署时无从下传（issue #21）",
            coordinator.contains("handshakeAllowedPackages = response.allowedPackages"),
        )
    }

    @Test
    fun `car deploy plan passes the allowlist into the engine argv`() {
        assertTrue(
            "VdServerDeployer.buildPlan 必须把手握白名单传给 VdDeploy.buildDeployPlan，" +
                "否则引擎仍然是 Allowlist: not handed down（issue #21）",
            deployer.contains("allowedPackages = sessionCoordinator.handshakeAllowedPackages"),
        )
        assertTrue(
            "车机部署日志必须打出让回签能对上的 allowlist 计数（issue #21）",
            deployer.contains("allowlist="),
        )
    }

    /** Gradle 测试工作目录不保证是仓库根，向上找到含源文件的目录。 */
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
