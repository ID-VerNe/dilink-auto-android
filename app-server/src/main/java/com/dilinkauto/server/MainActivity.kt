package com.dilinkauto.server

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.screen.CarShell
import com.dilinkauto.server.ui.theme.CarTheme

/**
 * Car-side launcher Activity. Owns only process-level concerns:
 *  - Service bind / unbind and forwarding the USB-attach intent.
 *  - Immersive mode (sticky fullscreen).
 *  - Rotation re-handshake: when the car panel rotates, push the new viewport
 *    dims into [CarConnectionService.onCarViewportChanged] so the phone
 *    recreates the VD at the new orientation without tearing down control.
 *
 * The Compose tree (streaming vs launch screen, nav bar, app-info dialog)
 * lives in [CarShell].
 */
class MainActivity : ComponentActivity() {

    private var carService: CarConnectionService? = null
    internal var pendingUsbDevice: android.hardware.usb.UsbDevice? = null
    private var serviceBound by mutableStateOf(false)

    // ─── issue #267：首帧 / 主线程归因插桩（仅 DEBUG 生效，不改任何行为） ───
    // 目的：把「Activity 重建 → 窗口首帧 → SurfaceView surfaceCreated」拆成可排序的时间线。
    // 现有日志只能看到 `waiting for surface Ns`，无法区分「主窗口没画出来」与
    // 「HOME 页本来就不需要 surface」。统一格式 `[Trace] t=<uptime> ...`，跨来源可对齐。

    private val traceStartMs = SystemClock.uptimeMillis()
    private val traceHandler by lazy { Handler(Looper.getMainLooper()) }
    private val pendingTrace = ArrayDeque<String>()
    private var firstDrawSeen = false
    private var firstFrameSeen = false

    @Volatile private var watchdogRunning = false
    @Volatile private var lastMainTickMs = 0L
    private var watchdogThread: Thread? = null

    private fun trace(tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.uptimeMillis()
        val line = "[Trace] t=$now (boot+${now - traceStartMs}ms) $tag $msg"
        Log.i("DiLinkTrace", line)
        val svc = carService
        if (svc != null) {
            svc.carLogSend(line)
        } else {
            // 服务尚未绑定：先缓存，onServiceConnected 后按原顺序 flush。
            synchronized(pendingTrace) { pendingTrace.addLast(line) }
        }
    }

    private fun flushPendingTrace() {
        val svc = carService ?: return
        synchronized(pendingTrace) {
            while (pendingTrace.isNotEmpty()) svc.carLogSend(pendingTrace.removeFirst())
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!watchdogRunning) return
            lastMainTickMs = SystemClock.uptimeMillis()
            traceHandler.postDelayed(this, 200L)
        }
    }

    /**
     * 主线程 watchdog：主线程每 200ms 刷新 `lastMainTickMs`，后台线程检测间隔。
     * 超过 1s 就打 `[MainThread] blocked Xms` + 当时真实的主线程栈（从后台线程抓，
     * 不是恢复后的残栈）。这是 #267「90 秒等待是否被主线程阻塞」的判据。
     */
    private fun startWatchdog() {
        if (!BuildConfig.DEBUG || watchdogRunning) return
        watchdogRunning = true
        lastMainTickMs = SystemClock.uptimeMillis()
        traceHandler.postDelayed(heartbeat, 200L)
        watchdogThread = Thread {
            var lastReportMs = 0L
            while (watchdogRunning) {
                try {
                    Thread.sleep(200L)
                } catch (_: InterruptedException) {
                    break
                }
                val now = SystemClock.uptimeMillis()
                val gap = now - lastMainTickMs
                if (gap > 1_000L && now - lastReportMs > 3_000L) {
                    lastReportMs = now
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .take(12)
                        .joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                    trace("[MainThread]", "blocked ${gap}ms stack=$stack")
                }
            }
        }.apply {
            name = "FirstFrameWatchdog"
            isDaemon = true
            start()
        }
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        traceHandler.removeCallbacks(heartbeat)
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    /**
     * issue #40：运行时定位授权。`ACCESS_FINE_LOCATION` 已在 manifest 声明，但 app-server
     * 此前 0 处运行时申请 → 海拔无法获取。首次进入主屏时一次性请求；拒绝则海拔显示 `—`，
     * 不阻塞启动、不影响其他功能（验收标准 2）。授权后通知 provider 恢复采样。
     */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            carService?.let { svc ->
                // 通过反射友好的内部方法触发；service 暴露 altitudeProvider 的恢复入口。
                svc.onLocationPermissionGranted()
            }
        }
        // 拒绝 → 什么都不做，provider 保持 null，UI 显示 `—`。
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            carService = (binder as CarConnectionService.LocalBinder).service
            serviceBound = true
            trace("[FirstFrame]", "serviceBound=true")
            flushPendingTrace()
            pendingUsbDevice?.let { device ->
                carService?.onUsbDeviceFromActivity(device)
                pendingUsbDevice = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carService = null
            serviceBound = false
            trace("[FirstFrame]", "serviceBound=false")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trace("[FirstFrame]", "onCreate")

        val serviceIntent = Intent(this, CarConnectionService::class.java).apply {
            action = CarConnectionService.ACTION_START
        }
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Handle USB device attached intent (from manifest intent-filter)
        handleUsbIntent(intent)

        // issue #40：首次进入主屏时请求定位授权（仅未授予时弹一次）。
        // ACCESS_FINE_LOCATION 已在 manifest 声明，但 app-server 此前无运行时申请。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        setContent {
            CarTheme {
                if (serviceBound) {
                    carService?.let { service ->
                        CarShell(service)
                    }
                }
            }
        }
        trace("[FirstFrame]", "setContent returned")

        // issue #267：decorView 首次 draw 与 Choreographer 首个帧回调是「窗口是否真的出帧」
        // 的硬判据：starting window 只有在真实窗口 drawn 之后才会被 WMS 替换。
        window.decorView.viewTreeObserver.addOnDrawListener(
            object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (!firstDrawSeen) {
                        firstDrawSeen = true
                        trace("[FirstFrame]", "decor first draw")
                    }
                }
            }
        )
        Choreographer.getInstance().postFrameCallback {
            if (!firstFrameSeen) {
                firstFrameSeen = true
                trace("[FirstFrame]", "choreographer first frame")
            }
        }
        startWatchdog()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    internal fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(
                android.hardware.usb.UsbManager.EXTRA_DEVICE
            )
            if (device != null) {
                android.util.Log.i("MainActivity", "USB device from intent: ${device.productName}")
                // Forward to service — it handles USB ADB
                val service = carService
                if (service != null) {
                    service.onUsbDeviceFromActivity(device)
                } else {
                    pendingUsbDevice = device
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        trace("[FirstFrame]", "windowFocus=$hasFocus")
        if (hasFocus) enableImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotatable car panel: when the viewport dimensions change (rotation),
        // re-handshake mid-stream so the phone recreates the VD at the new
        // orientation. The control connection is reused; only video/input and
        // the VD server are recycled.
        val dm = resources.displayMetrics
        carService?.onCarViewportChanged(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    override fun onDestroy() {
        trace("[FirstFrame]", "onDestroy")
        stopWatchdog()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
