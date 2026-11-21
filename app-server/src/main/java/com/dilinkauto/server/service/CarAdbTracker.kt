package com.dilinkauto.server.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.dilinkauto.protocol.AppTargets
import com.dilinkauto.protocol.Ports
import com.dilinkauto.protocol.VdDeploy
import com.dilinkauto.protocol.adb.UsbAdbConnection
import com.dilinkauto.server.R
import com.dilinkauto.server.adb.RemoteAdbController
import kotlinx.coroutines.*
import java.io.File

/**
 * 负责 USB 与 TCP ADB 双轨探测、权限管理、连接建立及 ADB 命令执行（从 [CarConnectionService] 剥离以遵循 SRP）。
 */
internal class CarAdbTracker(
    private val context: Context,
    private val carPrefs: CarPrefs,
    private val scope: CoroutineScope,
    private val log: (msg: String, level: String) -> Unit = { _, _ -> },
    private val setStatusMessage: (resId: Int, formatArgs: Array<out Any>) -> Unit,
    private val getPhoneHost: () -> String?,
    private val isHandshakeDone: () -> Boolean,
    private val isVdServerStarted: () -> Boolean,
    private val deployVdServer: () -> Unit,
    private val deployVdServerDirect: suspend (RemoteAdbController) -> Unit,
    private val onAdbReady: () -> Unit,
    private val onUsbDetachedMidSession: () -> Unit,
    /**
     * 自动重连闸门（issue #201）。
     *
     * USB 重新枚举、权限回调、TCP ADB 重拨都是**自动**入口：它们的共同后果是
     * `usbReady = true` → [onAdbReady] → `startConnection()`。用户点过「断开」之后
     * 这些入口必须先问一句；判定与抑制日志统一由 [CarSessionCoordinator.allowReconnect]
     * 持有，这里只负责不越过它。
     *
     * 必填、无默认值（issue #201 评审意见）：fail-open 的 `{ true }` 会让将来新增构造点
     * 忘接线时静默放行，闸门形同虚设。
     */
    private val autoReconnectAllowed: (ConnectTrigger) -> Boolean,
) {
    @Volatile var usbReady: Boolean = false
        internal set

    @Volatile var adbController: RemoteAdbController? = null
        internal set

    @Volatile var usbAdb: UsbAdbConnection? = null
        internal set

    @Volatile internal var currentUsbDevice: UsbDevice? = null

    /** Test seam for comparing USB devices under stubbed JVM test environments. */
    internal var isSameUsbDevice: (a: UsbDevice, b: UsbDevice) -> Boolean = { a, b ->
        a == b ||
            (a.deviceId != 0 && a.deviceId == b.deviceId) ||
            (a.deviceName.isNotBlank() && a.deviceName == b.deviceName)
    }

    @Volatile private var usbConnecting = false
    @Volatile private var tcpAdbConnecting = false
    @Volatile private var lastAdbHost: String? = null
    var noAdbCount: Int = 0
        internal set

    val usbPermissionAction = "com.dilinkauto.server.USB_PERMISSION"

    private var receiverRegistered = false

    internal val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        onUsbDeviceAttached(device)
                        startUsbTrack(scope)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detachedDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val currentDevice = currentUsbDevice
                    if (detachedDevice != null && currentDevice != null && !isSameUsbDevice(detachedDevice, currentDevice)) {
                        log("Non-ADB USB device detached (${detachedDevice.deviceName}) — ignoring", "I")
                        return
                    }
                    log("USB device detached", "I")
                    val wasAdbTrack = usbAdb != null
                    usbAdb?.close()
                    usbAdb = null
                    currentUsbDevice = null
                    usbReady = false
                    if (wasAdbTrack) {
                        onUsbDetachedMidSession()
                    }
                }
                usbPermissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && device != null) {
                        log("USB permission granted", "I")
                        connectUsbAdb(device)
                    } else {
                        log("USB permission denied", "I")
                    }
                }
            }
        }
    }

    fun registerUsbReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(usbPermissionAction)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    fun unregisterUsbReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        receiverRegistered = false
    }

    fun startUsbTrack(trackScope: CoroutineScope) {
        if (usbReady) return
        // issue #201：用户断开后不再扫设备、也不再重开 TCP ADB 探测（候选 B 的冷却落点）。
        if (!autoReconnectAllowed(ConnectTrigger.AUTO_ADB_READY)) return
        if (carPrefs.devMode) {
            log("Development mode active — using TCP ADB instead of USB", "I")
            startTcpAdbTrack(trackScope)
            return
        }
        trackScope.launch(Dispatchers.IO) {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            for (device in usbManager.deviceList.values) {
                if (UsbAdbConnection.findAdbInterface(device) != null) {
                    log("USB track: found existing device ${device.productName}", "I")
                    withContext(Dispatchers.Main) { onUsbDeviceAttached(device) }
                    return@launch
                }
            }
            log("USB track: no device found, waiting for attach broadcast", "I")
        }
    }

    fun startTcpAdbTrack(trackScope: CoroutineScope) {
        if (tcpAdbConnecting) return
        // issue #201：dev 模式下 TCP ADB 仍连着时，这条「恢复 usbReady」快路径会直接
        // 触发 onAdbReady → startConnection，用户断开后必须挡住。
        if (!autoReconnectAllowed(ConnectTrigger.AUTO_ADB_READY)) return
        val phoneHost = getPhoneHost()
        if (adbController?.isConnected == true && lastAdbHost == phoneHost) {
            if (!usbReady) {
                usbReady = true
                log("Dev mode: TCP ADB still connected — usbReady restored", "I")
                onAdbReady()
            }
            return
        }
        if (adbController?.isConnected == true && lastAdbHost != phoneHost && phoneHost != null) {
            log("Dev mode: phone IP changed $lastAdbHost -> $phoneHost, reconnecting TCP ADB", "I")
            adbController?.disconnect()
            adbController = null
            lastAdbHost = null
        }
        tcpAdbConnecting = true
        trackScope.launch(Dispatchers.IO) {
            try {
                // 阶段一：等手机 IP（最多 60s，与原逻辑一致）。
                var waitAttempts = 0
                while (isActive && !usbReady && getPhoneHost() == null && waitAttempts < 60) {
                    delay(1000)
                    waitAttempts++
                }
                var host: String = getPhoneHost() ?: run {
                    if (!usbReady) {
                        log("Dev mode: could not determine phone IP after ${waitAttempts}s", "I")
                    }
                    return@launch
                }

                // 阶段二：issue #269 —— 连接失败不再一次就放弃。原实现在
                // `connectTcpAdb` 之后无条件 `return@launch`，第一次失败后只能等
                // 下一次 startTcpAdbTrack 被调用，现场表现为 62s 空窗。
                for (attempt in 0 until TcpAdbReconnectPolicy.maxAttempts()) {
                    if (!isActive || usbReady) return@launch
                    val delayMs = TcpAdbReconnectPolicy.delayMsBeforeAttempt(attempt) ?: break
                    delay(delayMs)
                    if (usbReady) return@launch
                    host = getPhoneHost() ?: host
                    log("Dev mode: TCP ADB connecting to $host:${Ports.ADB_PORT} (attempt ${attempt + 1})", "I")
                    connectTcpAdb(host)
                    if (adbController?.isConnected == true) {
                        lastAdbHost = host
                        return@launch
                    }
                }
                if (!usbReady) {
                    log(
                        "Dev mode: TCP ADB connect gave up after " +
                            "${TcpAdbReconnectPolicy.maxAttempts()} attempts",
                        "W"
                    )
                }
            } finally {
                tcpAdbConnecting = false
            }
        }
    }

    suspend fun connectTcpAdb(host: String) {
        if (usbReady) return
        // issue #201：重拨会顺便 `am start` 拉起手机 App，用户断开后不许发生。
        if (!autoReconnectAllowed(ConnectTrigger.AUTO_ADB_READY)) return
        if (adbController?.isConnected == true) {
            log("Dev mode: TCP ADB already connected, skipping duplicate", "I")
            return
        }

        setStatusMessage(R.string.status_connecting_tcp_adb, arrayOf(host))
        val keyDir = File(context.filesDir, "adb_keys")
        val controller = RemoteAdbController(
            phoneHost = host,
            adbPort = Ports.ADB_PORT,
            virtualDisplayId = -1,
            keyDir = keyDir
        )

        if (!controller.connect()) {
            setStatusMessage(R.string.status_tcp_adb_failed, emptyArray())
            log("Dev mode: TCP ADB connection failed to $host:${Ports.ADB_PORT}", "I")
            return
        }

        adbController = controller
        setStatusMessage(R.string.status_tcp_adb_connected, emptyArray())
        log("Dev mode: TCP ADB connected to $host:${Ports.ADB_PORT}", "I")

        usbReady = true
        noAdbCount = 0

        if (isHandshakeDone() && !isVdServerStarted()) {
            log("TCP ADB ready — deploying VD server immediately", "I")
            deployVdServerDirect(controller)
        }
        try { controller.shell("am start -n ${AppTargets.PHONE_MAIN_ACTIVITY}") } catch (_: Exception) {}
        log("Dev mode: phone app launched via TCP ADB", "I")

        onAdbReady()
    }

    fun onUsbDeviceAttached(device: UsbDevice) {
        if (UsbAdbConnection.findAdbInterface(device) == null) return
        // issue #201：这里原先无条件把 userDisconnected 裸写回 false —— USB 重新枚举
        // （机身充电口插拔、手机 adbd/UI 重启都会触发）等于把用户的「不重连」抹掉，
        // 随后 connectUsbAdb → onAdbReady → startConnection 就会在 3~13s 后重建整条链路。
        // 重新枚举 != 用户重新连接：只放行闸门，绝不改写标志。
        if (!autoReconnectAllowed(ConnectTrigger.AUTO_ADB_READY)) return

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            log("USB device with permission: ${device.productName}", "I")
            connectUsbAdb(device)
        } else {
            log("Requesting USB permission for: ${device.productName}", "I")
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(usbPermissionAction), PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
        }
    }

    fun onUsbDeviceFromActivity(device: UsbDevice) {
        onUsbDeviceAttached(device)
    }

    fun connectUsbAdb(device: UsbDevice) {
        if (usbReady || usbConnecting) {
            log("USB ADB already ${if (usbReady) "connected" else "connecting"} — skipping", "I")
            return
        }
        // issue #201：USB 权限授权的广播回调也走这里，同样受自动闸门约束。
        if (!autoReconnectAllowed(ConnectTrigger.AUTO_ADB_READY)) return
        usbConnecting = true
        scope.launch(Dispatchers.IO) {
            setStatusMessage(R.string.status_connecting_usb, emptyArray())
            val adb = UsbAdbConnection(context)
            adb.setLogSink { msg -> log(msg, "I") }
            if (!adb.connect(device)) {
                usbConnecting = false
                currentUsbDevice = null
                setStatusMessage(R.string.status_usb_failed, emptyArray())
                log("USB ADB connection failed", "I")
                return@launch
            }
            usbAdb = adb
            currentUsbDevice = device
            setStatusMessage(R.string.status_usb_connected, emptyArray())
            log("USB ADB connected", "I")
            noAdbCount = 0

            val keyInfo = adb.keyDiagnostic()
            adb.shell("echo ${VdDeploy.shellQuote(keyInfo)} > /data/local/tmp/car-adb-key.log")
            log("ADB key: $keyInfo", "I")

            adb.shell("am start -n ${AppTargets.PHONE_MAIN_ACTIVITY}")
            log("Phone app launched via USB ADB", "I")

            usbConnecting = false
            usbReady = true

            if (isHandshakeDone() && !isVdServerStarted()) {
                log("USB ADB ready after handshake — deploying VD server", "I")
                deployVdServer()
            }

            onAdbReady()
        }
    }

    fun isAdbAvailable(): Boolean {
        val tcpOk = adbController?.isConnected == true
        val usbOk = usbAdb?.isConnected == true
        if (!tcpOk && !usbOk) {
            log("isAdbAvailable: tcp=${adbController != null}.${adbController?.isConnected} usb=${usbAdb != null}.${usbAdb?.isConnected}", "I")
        }
        return tcpOk || usbOk
    }

    fun resetAdbController() {
        adbController?.disconnect()
        adbController = null
        lastAdbHost = null
        usbReady = false
        log("ADB controller reset — scheduling backoff reconnect", "I")
        // issue #269：断网期间控制通道仍可能有心跳，checkAndAdvance 不会被触发；
        // 等外部 state pass 就是现场那 62s 空窗。重连必须由 reset 自己发起。
        scheduleTcpAdbReconnect()
    }

    /**
     * issue #269：reset 后的主动重连（退避 1s/2s/4s）。
     *
     * 只负责 TCP 轨：USB 已连或 `usbReady` 被别的路径恢复时立即结束；
     * `autoReconnectAllowed` 闸门保持 issue #201 的语义不变。
     */
    private fun scheduleTcpAdbReconnect() {
        if (!autoReconnectAllowed(ConnectTrigger.AUTO_ADB_READY)) return
        if (tcpAdbConnecting) return
        tcpAdbConnecting = true
        scope.launch(Dispatchers.IO) {
            try {
                for (attempt in 0 until TcpAdbReconnectPolicy.maxAttempts()) {
                    val delayMs = TcpAdbReconnectPolicy.delayMsBeforeAttempt(attempt) ?: break
                    delay(delayMs)
                    if (usbReady || usbAdb?.isConnected == true) return@launch
                    val host = getPhoneHost() ?: continue
                    log("Dev mode: TCP ADB reconnecting to $host:${Ports.ADB_PORT} (attempt ${attempt + 1})", "I")
                    connectTcpAdb(host)
                    if (adbController?.isConnected == true) {
                        lastAdbHost = host
                        return@launch
                    }
                }
                if (!usbReady) {
                    log(
                        "Dev mode: TCP ADB reconnect exhausted " +
                            "${TcpAdbReconnectPolicy.maxAttempts()} attempts",
                        "W"
                    )
                }
            } finally {
                tcpAdbConnecting = false
            }
        }
    }

    fun resetAdbReadiness() {
        if (usbAdb?.isConnected != true) {
            usbReady = false
            if (usbAdb == null) usbConnecting = false
        }
    }

    fun executeAdb(command: String, noWait: Boolean): Boolean {
        return when {
            adbController?.isConnected == true -> {
                if (noWait) adbController!!.shellNoWait(command)
                else adbController!!.shell(command)
            }
            usbAdb?.isConnected == true -> {
                if (noWait) {
                    usbAdb!!.shellNoWait(command) >= 0
                } else {
                    // ADB v1 shell 协议流并不通过数据报文回传命令退出码。
                    // 若调用方传入 probeExitCodeCommand，映射为带回显标记的 probeCommand，
                    // 通过输出是 "Y" 还是 "N" 判定引擎存活性（避免恒为 true 导致卡死 15 秒强杀）。
                    val isProbe = command == VdDeploy.probeExitCodeCommand || command == VdDeploy.probeCommand
                    val actualCommand = if (command == VdDeploy.probeExitCodeCommand) {
                        VdDeploy.probeCommand
                    } else {
                        command
                    }
                    val output = usbAdb!!.shell(actualCommand)
                    when {
                        output == null -> false
                        isProbe -> {
                            val trimmed = output.trim()
                            trimmed == "Y" || trimmed.equals("ALIVE", ignoreCase = true) || trimmed == "0"
                        }
                        else -> true
                    }
                }
            }
            else -> false
        }
    }

    fun destroy() {
        unregisterUsbReceiver()
        usbAdb?.close()
        usbAdb = null
        currentUsbDevice = null
        adbController?.disconnect()
        adbController = null
    }
}
