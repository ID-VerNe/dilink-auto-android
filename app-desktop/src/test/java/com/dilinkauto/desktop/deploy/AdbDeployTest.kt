package com.dilinkauto.desktop.deploy

import com.dilinkauto.desktop.DesktopConfig
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.VdDeployArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AdbDeploy] 纯命令拼装单测。
 *
 * 这些断言锁定的都是**线上事故的直接原因**，改错会静默失效（引擎起来了但日志 0 字节、
 * 或者两个引擎抢 VD），所以逐条钉住文本形状而不是只测"不为空"。
 */
class AdbDeployTest {

    private fun config(
        width: Int = 1280,
        height: Int = 720,
        dpiOverride: Int = 0,
        screenDpi: Int = 160,
    ) = DesktopConfig(
        phoneHost = "192.168.3.206",
        viewportWidth = width,
        viewportHeight = height,
        screenDpi = screenDpi,
        dpiOverride = dpiOverride,
    )

    @Test
    fun `serial 默认拼标准 adb 端口`() {
        assertEquals("192.168.3.206:${Ports.ADB_PORT}", AdbDeploy.serial("192.168.3.206"))
        assertEquals("10.0.0.5:4321", AdbDeploy.serial("10.0.0.5", 4321))
    }

    @Test
    fun `connectArgs 是 connect 加设备号`() {
        assertEquals(listOf("connect", "192.168.3.206:5555"), AdbDeploy.connectArgs("192.168.3.206"))
        assertEquals(listOf("connect", "192.168.3.206:4321"), AdbDeploy.connectArgs("192.168.3.206", 4321))
    }

    @Test
    fun `shellArgs 把整条命令作为单个参数交给设备 shell`() {
        // 整条命令必须是一个 argv：拆开会被本地 shell 先解析，重定向/分号会落到 PC 上
        val args = AdbDeploy.shellArgs("192.168.3.206:5555", "pkill -f [P]ipelineServer 2>/dev/null")
        assertEquals(listOf("-s", "192.168.3.206:5555", "shell", "pkill -f [P]ipelineServer 2>/dev/null"), args)
    }

    @Test
    fun `resolveDpi override 为正时优先用它`() {
        assertEquals(160, AdbDeploy.resolveDpi(config(dpiOverride = 0, screenDpi = 160), 1280, 720))
        assertEquals(240, AdbDeploy.resolveDpi(config(dpiOverride = 240, screenDpi = 160), 1280, 720))
        // 负数等同自动（与手机侧 coerceDpiOverride 同语义）
        assertEquals(160, AdbDeploy.resolveDpi(config(dpiOverride = -1, screenDpi = 160), 1280, 720))
    }

    /**
     * WIN-08：overrides 必须按协议区间（[VdDeployArgs] 的 120..480）夹紧。
     * 此前直通不夹紧 —— 同一个 UI 值在 Shizuku 路径落到 480、在 ADB 路径原样发 640。
     */
    @Test
    fun `resolveDpi 把 override 夹进协议区间`() {
        assertEquals(
            VdDeployArgs.DPI_OVERRIDE_MAX,
            AdbDeploy.resolveDpi(config(dpiOverride = 640), 1280, 720),
        )
        assertEquals(
            VdDeployArgs.DPI_OVERRIDE_MIN,
            AdbDeploy.resolveDpi(config(dpiOverride = 50), 1280, 720),
        )
    }

    /**
     * WIN-08：自动（0）时用 [VideoConfig.calculateOptimalDpi] 按视口标定，
     * 而不是把 `screenDpi` 原样顶上去 —— 与手机侧 `VdDimensions` 同一个函数。
     */
    @Test
    fun `resolveDpi 自动时按视口标定`() {
        // 1280x720 横屏、上报 160 → 落在 120..320 内取 min(160, maxSafe=160)
        assertEquals(160, AdbDeploy.resolveDpi(config(dpiOverride = 0, screenDpi = 160), 1280, 720))
        // 1920x1080 且手机没报 DPI → 黄金比例 (1080*0.52*160)/385 ≈ 233
        assertEquals(233, AdbDeploy.resolveDpi(config(dpiOverride = 0, screenDpi = 0), 1920, 1080))
    }

    @Test
    fun `plan 用偶数对齐后的视口并把背景参数固定为前台`() {
        // 奇数边长会被 H.264 编码器拒绝，plan 里必须已经对齐过
        val plan = AdbDeploy.plan(config(width = 1281, height = 721))

        assertTrue("视口未偶数对齐: ${plan.args}", plan.args.startsWith("1280 720 "))
        assertEquals(VdDeploy.killCommand, plan.killCommand)
        // 硬约束：前台 exec，绝不能后台化
        assertTrue("应以 exec 前台启动: ${plan.launchCommand}", plan.launchCommand.contains("exec app_process"))
        // 注意不能简单查 "&"：日志重定向 2>&1 本身就含 &，要查后台化后缀 " &"
        assertFalse("不能后台化（行尾不能有 &）: ${plan.launchCommand}", plan.launchCommand.trimEnd().endsWith("&"))
        assertFalse("不能带 setsid: ${plan.launchCommand}", plan.launchCommand.contains("setsid"))
    }

    @Test
    fun `plan 固定回环推流地址与默认 jar 路径`() {
        val plan = AdbDeploy.plan(config())

        // VD server 与手机 app 同机，推流目标是回环（与车机端一致）
        assertTrue("推流地址应为 127.0.0.1: ${plan.args}", plan.args.contains(" 127.0.0.1 "))
        // 路径一律 shell 引用（WIN-09）
        assertTrue(
            "应使用默认 jar（且被引用）: ${plan.launchCommand}",
            plan.launchCommand.contains("CLASSPATH=${VdDeploy.shellQuote(VdDeploy.JAR_PATH)}"),
        )
        assertTrue(
            "日志应指向设备侧（且被引用）: ${plan.launchCommand}",
            plan.launchCommand.contains(VdDeploy.shellQuote(VdDeploy.LOG_PATH)),
        )
        assertTrue("应含主类: ${plan.launchCommand}", plan.launchCommand.contains(VdDeploy.MAIN_CLASS))
    }

    @Test
    fun `plan 用握手响应里的 jar 路径`() {
        val jar = "/sdcard/DiLinkAuto/vd-server.jar"
        val plan = AdbDeploy.plan(config(), jarPath = jar)
        assertTrue(plan.launchCommand.contains("CLASSPATH=${VdDeploy.shellQuote(jar)}"))
    }

    @Test
    fun `plan 对空 jar 路径回退到默认`() {
        val plan = AdbDeploy.plan(config(), jarPath = "   ")
        assertTrue(plan.launchCommand.contains("CLASSPATH=${VdDeploy.shellQuote(VdDeploy.JAR_PATH)}"))
    }
}
