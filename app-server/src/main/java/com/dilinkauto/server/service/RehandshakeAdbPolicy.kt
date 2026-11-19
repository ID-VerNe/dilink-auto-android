package com.dilinkauto.server.service

/**
 * issue #22：重握手时是否要丢弃并重建 TCP ADB 控制器。
 *
 * 只有 TCP 控制器存在时才需要（它承载了旧引擎的 `exec app_process` 流，kill 后 socket
 * 半死但 `isConnected` 仍可能为 true）；纯 USB ADB 不能重置，否则会重复打开 USB 连接。
 */
internal fun shouldRebuildAdbOnRehandshake(hasTcpController: Boolean): Boolean = hasTcpController
