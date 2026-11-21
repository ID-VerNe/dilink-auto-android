package com.dilinkauto.server.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * issue #269：源码守卫 —— reset 必须自己发起重连，且失败不能一次就放弃。
 *
 * 行为验证要等上车（`ADB controller reset → TCP ADB connected ≤ 10s`），
 * 这里只钉住两条最容易在后续重构里被改回去的代码路径。
 */
class CarAdbTrackerReconnectGuardTest {

    private val src = File("src/main/java/com/dilinkauto/server/service/CarAdbTracker.kt").readText()

    @Test
    fun `resetAdbController 必须调度主动重连`() {
        val start = src.indexOf("fun resetAdbController()")
        assertTrue("找不到 resetAdbController", start >= 0)
        val end = src.indexOf("fun resetAdbReadiness()", start)
        assertTrue("找不到 resetAdbReadiness 边界", end > start)
        val block = src.substring(start, end)
        assertTrue("reset 后必须调用 scheduleTcpAdbReconnect()", block.contains("scheduleTcpAdbReconnect()"))
        assertTrue("旧的 will reconnect on next state pass 文案不应保留", !block.contains("will reconnect on next state pass"))
    }

    @Test
    fun `startTcpAdbTrack 必须按策略重试而不是一次就放弃`() {
        val start = src.indexOf("fun startTcpAdbTrack(")
        assertTrue("找不到 startTcpAdbTrack", start >= 0)
        val end = src.indexOf("suspend fun connectTcpAdb(", start)
        assertTrue("找不到 connectTcpAdb 边界", end > start)
        val block = src.substring(start, end)
        assertTrue("必须使用 TcpAdbReconnectPolicy", block.contains("TcpAdbReconnectPolicy.maxAttempts()"))
        assertTrue("失败后必须能继续循环", block.contains("delayMsBeforeAttempt"))
    }

    @Test
    fun `主动重连必须尊重自动重连闸门`() {
        val start = src.indexOf("private fun scheduleTcpAdbReconnect()")
        assertTrue("找不到 scheduleTcpAdbReconnect", start >= 0)
        val end = src.indexOf("fun resetAdbReadiness()", start)
        assertTrue("找不到边界", end > start)
        val block = src.substring(start, end)
        assertTrue(
            "必须过 autoReconnectAllowed(AUTO_ADB_READY) 闸门",
            block.contains("autoReconnectAllowed(ConnectTrigger.AUTO_ADB_READY)")
        )
    }
}
