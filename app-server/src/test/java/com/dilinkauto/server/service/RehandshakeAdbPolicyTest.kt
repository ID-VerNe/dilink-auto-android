package com.dilinkauto.server.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #22：重握手是否要丢弃并重建 ADB 控制器的判定钉子。
 *
 * 只有 TCP 控制器存在时才需要重建（它承载旧引擎的 `exec app_process` 流，kill 后半死）；
 * 纯 USB ADB 绝不能重置，否则会重复打开一条 USB 连接。
 */
class RehandshakeAdbPolicyTest {

    @Test
    fun `tcp controller present must rebuild`() {
        assertTrue(shouldRebuildAdbOnRehandshake(hasTcpController = true))
    }

    @Test
    fun `no tcp controller must keep usb connection`() {
        assertFalse(shouldRebuildAdbOnRehandshake(hasTcpController = false))
    }
}
