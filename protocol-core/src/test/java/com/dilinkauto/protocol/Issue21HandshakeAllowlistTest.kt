package com.dilinkauto.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #21：握手响应必须把「车机可见应用白名单」带给车机，车机再把它拼进
 * vd-server 的 argv（`ALLOWED` 槽位）。
 *
 * 缺陷形态（2026-11-19 实车）：车机端 ADB 部署路径拿不到手机 prefs，也没有任何
 * 下传通道，`VdServerDeployer.buildPlan` 只能传空集合，引擎日志是
 * `Allowlist: not handed down (no recents filtering)`，建屏恢复把 Shizuku 管理器
 * 淘宝等隐私应用拉到车机屏（车机 App 列表只有 3/123）。
 *
 * 白名单不能取车机的 `APP_LIST`：车机在收到握手响应的那一刻就部署引擎，而
 * `APP_LIST` 要等 VD 生命周期连上（引擎已启动）才由手机发出 —— 等它只会死锁。
 */
class Issue21HandshakeAllowlistTest {

    private fun response(allowed: Set<String>) = HandshakeResponse(
        accepted = true,
        deviceName = "MEIZU 20 Inf",
        displayWidth = 1204,
        displayHeight = 660,
        vdServerJarPath = VdDeploy.JAR_PATH,
        connectionMethod = CONNECTION_METHOD_USB_ADB,
        vdDpi = 234,
        vdWidth = 1204,
        vdHeight = 660,
        supportedFeatures = 0,
        allowedPackages = allowed,
    )

    @Test
    fun `handshake response round trips the allowlist`() {
        val allowed = setOf("com.autonavi.minimap", "com.baidu.BaiduMap")
        val decoded = HandshakeResponse.decode(response(allowed).encode())

        assertEquals("握手应携带白名单包名集合（issue #21）", allowed, decoded.allowedPackages)
        // 尾字段不得挤坏前面的既有字段。
        assertEquals("MEIZU 20 Inf", decoded.deviceName)
        assertEquals(1204, decoded.displayWidth)
        assertEquals(660, decoded.displayHeight)
        assertEquals(234, decoded.vdDpi)
        assertEquals(1204, decoded.vdWidth)
        assertEquals(660, decoded.vdHeight)
        assertEquals(CONNECTION_METHOD_USB_ADB, decoded.connectionMethod)
        assertEquals(VdDeploy.JAR_PATH, decoded.vdServerJarPath)
        assertTrue(decoded.accepted)
    }

    @Test
    fun `empty allowlist stays empty and is not confused with a package named dash`() {
        // 未配置白名单 = 空集合 = 引擎不过滤；哨兵 `-` 只出现在 argv 里。
        val decoded = HandshakeResponse.decode(response(emptySet()).encode())
        assertTrue("空集合必须解码回空集合", decoded.allowedPackages.isEmpty())
    }

    @Test
    fun `legacy phone without the trailing field decodes to an empty allowlist`() {
        // 老手机不会带这 2+N 字节：把新编码的尾字段整体砍掉，等价于老布局。
        val full = response(emptySet()).encode()
        val legacy = full.copyOfRange(0, full.size - (2 + VdDeployArgs.ALLOWED_ANY.length))

        val decoded = HandshakeResponse.decode(legacy)
        assertTrue("老手机未下传白名单时必须是空集合（引擎不过滤，保持旧行为）", decoded.allowedPackages.isEmpty())
        assertEquals("MEIZU 20 Inf", decoded.deviceName)
        assertEquals(1204, decoded.displayWidth)
        assertEquals(234, decoded.vdDpi)
    }

    @Test
    fun `illegal package names are dropped on the wire`() {
        // 这个字符串最终会进 shell argv，任何 shell 元字符都必须在编码端被丢掉。
        val decoded = HandshakeResponse.decode(
            response(setOf("com.autonavi.minimap", "com.evil; rm -rf /")).encode()
        )
        assertEquals(setOf("com.autonavi.minimap"), decoded.allowedPackages)
        assertFalse("非法包名不得出现在线上载荷里", decoded.allowedPackages.any { it.contains(";") })
    }

    @Test
    fun `car deploy plan built from the handshake fills the ALLOWED argv slot`() {
        // 车机端 VdServerDeployer.buildPlan 的全部输入就是这些：视口、DPI、fps、
        // 码率与握手响应带来的 allowlist。这条断言把「握手 → argv → 引擎解析」
        // 串成一条可执行契约。
        val allowed = setOf("com.autonavi.minimap", "com.baidu.BaiduMap")
        val decoded = HandshakeResponse.decode(response(allowed).encode())

        val plan = VdDeploy.buildDeployPlan(
            jarPath = VdDeploy.JAR_PATH,
            logPath = VdDeploy.LOG_PATH,
            vdWidth = 1204, vdHeight = 660, dpi = decoded.vdDpi,
            encodeWidth = 1204, encodeHeight = 660,
            phoneHost = "127.0.0.1", fps = 24,
            bitrate = 4_000_000,
            background = false,
            carHost = VdDeployArgs.CAR_HOST_ANY,
            allowedPackages = decoded.allowedPackages,
        )

        val slots = plan.args.split(' ')
        assertEquals("argv 槽位数必须仍是 12（W H DPI PHONE_HOST EW EH FPS BITRATE CAR_HOST ALLOWED SESSION DPI_SOURCE）", 12, slots.size)
        assertEquals(
            "车机部署 argv 的 ALLOWED 槽必须等于握手带来的白名单（引擎据此过滤建屏恢复）",
            allowed,
            VdDeployArgs.parseAllowedPackages(slots[9]),
        )
    }

    @Test
    fun `car deploy plan without an allowlist keeps the legacy sentinel`() {
        val decoded = HandshakeResponse.decode(response(emptySet()).encode())
        val plan = VdDeploy.buildDeployPlan(
            jarPath = VdDeploy.JAR_PATH,
            logPath = VdDeploy.LOG_PATH,
            vdWidth = 1204, vdHeight = 660, dpi = 234,
            encodeWidth = 1204, encodeHeight = 660,
            phoneHost = "127.0.0.1", fps = 24,
            background = false,
            allowedPackages = decoded.allowedPackages,
        )
        assertEquals(
            "未下传时必须仍是 `-`（引擎 `Allowlist: not handed down`，行为与修复前一致）",
            VdDeployArgs.ALLOWED_ANY,
            plan.args.split(' ')[9],
        )
    }
}
