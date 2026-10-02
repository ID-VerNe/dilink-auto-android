package com.dilinkauto.client.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import android.os.UserHandle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dilinkauto.client.ClientApp
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.client.display.VirtualDisplayClient
import com.dilinkauto.protocol.*
import dadb.Dadb
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectionService : Service() {

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var controlConnection: Connection? = null
    @Volatile private var vdClient: VirtualDisplayClient? = null
    private var pendingAppLaunch: String? = null
    private var vdWaitJob: Job? = null
    private var handshakeJob: Job? = null
    private var targetFps = 30
    private var serviceRegistration: Discovery.ServiceRegistration? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionLoopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkChangeDebounce: Job? = null
    private var autoUpdateAttempted = false
    private var autoUpdateFailedAt = 0L
    private lateinit var carAppInstaller: CarAppInstaller

    enum class State { IDLE, WAITING, CONNECTED, STREAMING }

    override fun onBind(intent: Intent?): IBinder? = null

    private var packageRemovedReceiver: BroadcastReceiver? = null
    @Volatile private var savedDefaultIme: String? = null

    override fun onCreate() {
        super.onCreate()
        FileLog.loadEnabled(getSharedPreferences("dilinkauto", MODE_PRIVATE))
        FileLog.rotate() // Archive previous log, start fresh
        // Clear stale static state from previous service instance
        activeConnection = null
        _serviceState.value = State.IDLE
        acquireWakeLock()
        registerNetworkCallback()
        registerPackageRemovedReceiver()
        deployAssets()
        logDeviceInfo()
        cacheDefaultIme()
        UpdateManager.checkForUpdate(force = false)
        // Wire the extracted locator's WiFi dependency (avoids passing the Service
        // into CarIpLocator; the locator is a plain object for unit-testability).
        @Suppress("DEPRECATION")
        CarIpLocator.wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
        carAppInstaller = CarAppInstaller(this) { msg -> _installStatusStatic.value = msg }
    }

    private fun cacheDefaultIme() {
        try {
            val currentIme = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!currentIme.isNullOrBlank() && currentIme != "null" && !currentIme.contains("linkpc", ignoreCase = true)) {
                savedDefaultIme = currentIme
                getSharedPreferences("dilinkauto", MODE_PRIVATE).edit().putString("saved_default_ime", currentIme).apply()
                FileLog.i(TAG, "Cached default IME: $currentIme")
            }
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to cache default IME: ${e.message}")
        }
    }

    private fun logDeviceInfo() {
        val info = buildString {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            val dm = resources.displayMetrics

            appendLine("── Device Info ──")
            appendLine("model=${android.os.Build.MODEL} manufacturer=${android.os.Build.MANUFACTURER}")
            appendLine("product=${android.os.Build.PRODUCT} device=${android.os.Build.DEVICE}")
            appendLine("android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
            appendLine("display=${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi density=${dm.density}")
            appendLine("cores=${Runtime.getRuntime().availableProcessors()}")
            appendLine("abi=${android.os.Build.SUPPORTED_ABIS?.joinToString(",") ?: "?"}")
            appendLine("heapMax=${Runtime.getRuntime().maxMemory()} heapTotal=${Runtime.getRuntime().totalMemory()} heapFree=${Runtime.getRuntime().freeMemory()}")
            appendLine("totalMem=${mi.totalMem} availMem=${mi.availMem} lowMemory=${mi.lowMemory}")
        }
        FileLog.i(TAG, info)
    }

    private fun registerPackageRemovedReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    if (replacing) return // ignore updates (app is reinstalled immediately)
                    FileLog.i(TAG, "Package removed: $pkg — notifying car")
                    val conn = controlConnection
                    if (conn != null && conn.isConnected) {
                        try {
                            conn.sendData(DataMsg.APP_UNINSTALLED, pkg.toByteArray(Charsets.UTF_8))
                        } catch (e: Exception) {
                            FileLog.w(TAG, "Failed to send APP_UNINSTALLED: ${e.message}")
                        }
                        // Resend the full app list so car has accurate state
                        sendAppList()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(receiver, filter)
        packageRemovedReceiver = receiver
    }

    @Volatile
    private var assetsReady = false

    private fun deployAssets() {
        serviceScope.launch(Dispatchers.IO) {
            val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto")
            dir.mkdirs()
            extractAsset("vd-server.jar", java.io.File(dir, "vd-server.jar"))
            extractAsset("app-server.apk", java.io.File(filesDir, "app-server.apk"))
            assetsReady = true
        }
    }

    private suspend fun ensureAssetsReady() {
        if (assetsReady) return
        val apkFile = java.io.File(filesDir, "app-server.apk")
        repeat(50) {
            if (apkFile.exists()) return
            delay(100)
        }
    }

    private fun extractAsset(assetName: String, target: java.io.File) {
        try {
            val assetBytes = assets.open(assetName).use { it.readBytes() }
            val assetCrc = java.util.zip.CRC32().apply { update(assetBytes) }.value

            if (target.exists()) {
                val fileCrc = java.util.zip.CRC32().apply { update(target.readBytes()) }.value
                if (fileCrc == assetCrc) {
                    FileLog.i(TAG, "$assetName up-to-date (crc=$assetCrc)")
                    return
                }
            }

            val tmp = java.io.File("${target.absolutePath}.tmp")
            tmp.writeBytes(assetBytes)
            tmp.renameTo(target)
            FileLog.i(TAG, "$assetName deployed to ${target.absolutePath} (${assetBytes.size} bytes, crc=$assetCrc)")
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to extract $assetName: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                FileLog.i(TAG, "Network available: $network")
                networkChangeDebounce?.cancel()
                val state = _serviceState.value
                if (state == State.WAITING) {
                    FileLog.i(TAG, "New network while WAITING — restarting listen loop")
                    cleanupSession()
                    connectionLoopJob?.cancel()
                    startConnectionLoop()
                }
            }
            override fun onLost(network: Network) {
                // Only react if the lost network is the one our connection uses.
                // Ignore unrelated network drops (mobile data, other WiFi, etc.)
                val conn = controlConnection
                if (conn != null && conn.isConnected) {
                    val cm2 = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                    val activeNet = cm2.activeNetwork
                    // If another network is still active and our connection has it, ignore
                    if (activeNet != null && activeNet != network) {
                        FileLog.i(TAG, "Network lost: $network (not our active network $activeNet — ignoring)")
                        return
                    }
                }
                FileLog.i(TAG, "Network lost: $network — debouncing before reacting")
                // Debounce: delay 3s before reacting to transient network flaps.
                // 4G changes can cause brief hotspot resets that recover immediately.
                networkChangeDebounce?.cancel()
                networkChangeDebounce = serviceScope.launch {
                    delay(3000)
                    if (controlConnection?.isConnected != true) {
                        FileLog.i(TAG, "Network lost confirmed — resetting connection")
                    }
                    resetConnectionForNetworkChange()
                }
            }
        }
        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun resetConnectionForNetworkChange() {
        val state = _serviceState.value
        when (state) {
            State.WAITING -> {
                FileLog.i(TAG, "Network changed while WAITING — restarting listen loop")
                cleanupSession()
                connectionLoopJob?.cancel()
                startConnectionLoop()
            }
            State.CONNECTED, State.STREAMING -> {
                // Proactive disconnect — don't wait 10s for heartbeat timeout.
                // The connection is likely dead if the network interface changed.
                FileLog.i(TAG, "Network lost while $state — proactive disconnect")
                cleanupSession()
                // listenAndHandleOneConnection() will restart via its finally block
            }
            else -> {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_waiting))
                startConnectionLoop()
            }
            ACTION_STOP -> {
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_INSTALL_CAR -> {
                val explicitIp = intent?.getStringExtra("car_ip")
                installCarApp(explicitIp)
            }
        }
        return START_STICKY
    }

    // ─── Connection Loop ───

    private fun startConnectionLoop() {
        // Reset auto-update state on explicit start — gives user a fresh chance
        autoUpdateAttempted = false
        autoUpdateFailedAt = 0L
        connectionLoopJob?.cancel()
        connectionLoopJob = serviceScope.launch {
            // Register mDNS in background — don't block the listen loop.
            // NsdManager may never invoke the callback if there's no active network,
            // which would hang the coroutine indefinitely.
            launch {
                try {
                    if (serviceRegistration == null) {
                        serviceRegistration = withTimeoutOrNull(5000) {
                            Discovery.registerService(
                                this@ConnectionService,
                                port = Discovery.DEFAULT_PORT,
                                deviceName = android.os.Build.MODEL
                            )
                        }
                        if (serviceRegistration == null) {
                            FileLog.w(TAG, "mDNS registration timed out (no network?)")
                        }
                    }
                } catch (e: Exception) {
                    FileLog.e(TAG, "mDNS registration failed", e)
                }
            }

            // Listen loop starts immediately — works on 0.0.0.0 even without WiFi.
            // The car can connect once a network (hotspot/WiFi) comes up.
            while (isActive) {
                listenAndHandleOneConnection()
            }
        }
    }

    private suspend fun listenAndHandleOneConnection() {
        _serviceState.value = State.WAITING
        updateNotification(R.string.notification_waiting)
        FileLog.i(TAG, "Listening for car connection on port ${Discovery.DEFAULT_PORT}...")

        try {
            // ─── Accept control connection (port 9637) ───
            val ctrl = Connection.accept(Discovery.DEFAULT_PORT, serviceScope)
            controlConnection = ctrl
            activeConnection = ctrl
            _serviceState.value = State.CONNECTED
            updateNotification(R.string.notification_connected)
            FileLog.i(TAG, "Car connected (control), waiting for handshake")

            ctrl.onFrames(Channel.CONTROL) { frame -> handleControlFrame(frame) }
            ctrl.onFrames(Channel.DATA) { frame -> handleDataFrame(frame) }

            val disconnected = CompletableDeferred<Unit>()
            ctrl.onLog { msg -> FileLog.w(TAG, "ControlConn: $msg") }
            ctrl.onDisconnect {
                FileLog.i(TAG, "Car disconnected (control)")
                disconnected.complete(Unit)
            }

            ctrl.start() // heartbeat enabled (default)
            disconnected.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLog.w(TAG, "Connection error: ${e.message}")
        } finally {
            cleanupSession()
            delay(1000)
        }
    }


    // ─── Frame Handlers ───

    private fun handleControlFrame(frame: FrameCodec.Frame) {
        FileLog.d(TAG, "Control frame: type=0x${frame.messageType.toString(16)}")
        when (frame.messageType) {
            ControlMsg.HANDSHAKE_REQUEST -> {
                val req = HandshakeRequest.decode(frame.payload)
                FileLog.i(TAG, "Handshake from car: ${req.deviceName} ${req.screenWidth}x${req.screenHeight}")
                handleHandshake(req)
            }
            // APP_SHORTCUTS still goes through phone — VD has no direct control channel to car
            ControlMsg.APP_SHORTCUTS -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                FileLog.i(TAG, "Car requested shortcuts for: $pkg")
                sendAppShortcuts(pkg)
            }
            // LAUNCH_APP, GO_BACK, GO_HOME, APP_UNINSTALL, APP_INFO, APP_SHORTCUT_ACTION
            // now go directly Car → VD via port 9639 Channel.CONTROL
        }
    }

    private fun handleHandshake(request: HandshakeRequest) {
        val conn = controlConnection ?: return
        FileLog.i(TAG, "Car display: ${request.screenWidth}x${request.screenHeight} @${request.screenDpi}dpi fps=${request.targetFps}")
        targetFps = request.targetFps

        cacheDefaultIme()

        // Mid-stream re-handshake: the car rotated and is reusing the control
        // connection. Tear down the old VD before creating a new one at the
        // new orientation. Old VD exits via its watchdog, restoring the panel.
        if (vdClient != null) {
            FileLog.i(TAG, "Re-handshake: tearing down old VD (rotation)")
            vdClient?.stopVdServer()
            vdClient?.disconnect()
            vdClient = null
        }

        // Create VD at car viewport size.
        // Auto-calibrate DPI: ensure portrait apps get at least ~380dp logical width in landscape
        // (iPad-like phone app display) instead of crushing into an unusable 140dp sliver.
        var vdWidth = request.screenWidth and 0x7FFFFFFE.toInt()
        var vdHeight = request.screenHeight and 0x7FFFFFFE.toInt()
        
        // Anti-Crop Scale: Ensure Virtual Display width is at least the phone's physical width.
        // Many Chinese ROMs (like Meizu, Xiaomi) hardcode the IME width to the physical display width.
        // If the car viewport is narrower than the phone, the keyboard gets horizontally chopped.
        // Scaling up the VD preserves the car's aspect ratio while satisfying the OS width.
        val dm = resources.displayMetrics
        val isCarLandscape = vdWidth > vdHeight
        val isPhoneLandscape = dm.widthPixels > dm.heightPixels
        val phonePhysicalWidth = if (isPhoneLandscape == isCarLandscape) dm.widthPixels else dm.heightPixels
        if (vdWidth < phonePhysicalWidth) {
            val scale = phonePhysicalWidth.toFloat() / vdWidth
            vdWidth = (vdWidth * scale).toInt() and 0x7FFFFFFE.toInt()
            vdHeight = (vdHeight * scale).toInt() and 0x7FFFFFFE.toInt()
            FileLog.i(TAG, "Scaled VD to ${vdWidth}x${vdHeight} (scale=$scale) to prevent IME crop")
        }
        val displayDpi = VideoConfig.calculateOptimalDpi(vdWidth, vdHeight, request.screenDpi)
        FileLog.i(TAG, "VD: ${vdWidth}x${vdHeight} @${displayDpi}dpi (car reported ${request.screenDpi}dpi, auto-calibrated optimal touch scale)")

        // Open lifecycle channel if not already open (survives re-handshakes)
        if (vdClient == null) {
            val lifecycleClient = VirtualDisplayClient(serviceScope, this@ConnectionService)
            lifecycleClient.onStackEmpty = {
            val c = controlConnection
            if (c?.isConnected == true) {
                try { c.sendControl(ControlMsg.VD_STACK_EMPTY) } catch (_: Exception) {}
            }
        }
        lifecycleClient.onFocusedApp = { pkg ->
            val c = controlConnection
            if (c?.isConnected == true) {
                try { c.sendControl(ControlMsg.FOCUSED_APP, pkg.toByteArray(Charsets.UTF_8)) } catch (_: Exception) {}
            }
        }
        lifecycleClient.onDisplayReady = {
            val c = controlConnection
            if (c?.isConnected == true) {
                try {
                    c.sendControl(ControlMsg.VD_PORTS_BOUND)
                    FileLog.i(TAG, "Sent VD_PORTS_BOUND to car — direct video/input streaming")
                } catch (e: Exception) {
                    FileLog.e(TAG, "Failed to send VD_PORTS_BOUND", e)
                }
            }
        }
            lifecycleClient.startListening(VirtualDisplayClient.SERVER_PORT)
            vdClient = lifecycleClient
            FileLog.i(TAG, "VD lifecycle channel open on localhost:${VirtualDisplayClient.SERVER_PORT}")
        }

        val vdJarPath = java.io.File(
            java.io.File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto"),
            "vd-server.jar"
        ).absolutePath
        val connMethod = if (ShizukuManager.checkPermission()) CONNECTION_METHOD_SHIZUKU else CONNECTION_METHOD_USB_ADB
        val resp = HandshakeResponse(
            accepted = true,
            deviceName = android.os.Build.MODEL,
            displayWidth = request.screenWidth,
            displayHeight = request.screenHeight,
            virtualDisplayId = -1,
            adbPort = 5555,
            vdServerJarPath = vdJarPath,
            connectionMethod = connMethod,
            vdDpi = displayDpi
        )
        handshakeJob?.cancel()
        handshakeJob = serviceScope.launch(Dispatchers.IO) {
            // Check version BEFORE sending response — determines the flow.
            // When car sends empty appVersionName (pre-0.17.0), fall back to
            // versionCode on BOTH sides so integers are compared correctly.
            val carHasSemver = request.appVersionName.isNotEmpty()
            val carVersionName = if (carHasSemver) request.appVersionName
                else request.appVersionCode.toString()
            val myVersionName = if (carHasSemver) {
                packageManager.getPackageInfo(packageName, 0).let {
                    it.versionName ?: @Suppress("DEPRECATION") it.versionCode.toString()
                }
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode.toString()
            }
            val updateCooldown = autoUpdateFailedAt > 0L &&
                System.currentTimeMillis() - autoUpdateFailedAt < 5 * 60 * 1000L
            val needsUpdate = UpdateManager.compareVersions(myVersionName, carVersionName) > 0
                && !autoUpdateAttempted && !updateCooldown

            if (needsUpdate) {
                try {
                    conn.sendControl(ControlMsg.HANDSHAKE_RESPONSE, resp.encode())
                    FileLog.i(TAG, "Handshake response sent (update needed)")
                } catch (e: Exception) {
                    FileLog.e(TAG, "Failed to send handshake response", e)
                    return@launch
                }

                try {
                    conn.sendControl(ControlMsg.UPDATING_CAR)
                    FileLog.i(TAG, "Sent UPDATING_CAR to car")
                } catch (_: Exception) {}

                autoUpdateAttempted = true
                FileLog.i(TAG, "Car app outdated — updating, waiting for reconnect")
                _installStatusStatic.value = getString(R.string.status_auto_update, carVersionName, myVersionName)
                autoUpdateCarApp(conn)
                delay(2000)
                FileLog.i(TAG, "Update initiated — disconnecting to wait for car reconnect")
                withContext(Dispatchers.Main) { cleanupSession() }
            } else {
                try {
                    conn.sendControl(ControlMsg.HANDSHAKE_RESPONSE, resp.encode())
                    FileLog.i(TAG, "Handshake response sent")
                } catch (e: Exception) {
                    FileLog.e(TAG, "Failed to send handshake response", e)
                    return@launch
                }

                if (UpdateManager.compareVersions(myVersionName, carVersionName) > 0) {
                    FileLog.i(TAG, "Car app outdated — update already attempted, proceeding")
                } else {
                    FileLog.i(TAG, "Car app up-to-date ($carVersionName)")
                }

                // If Shizuku is available, deploy VD server directly BEFORE waiting for lifecycle connection
                if (ShizukuManager.isAvailable) {
                    startVdServerViaShizuku(request.screenWidth, request.screenHeight, vdWidth, vdHeight, displayDpi)
                }

                // Wait for VD to connect on the lifecycle channel already opened.
                // Runs inside handshakeJob — cancelled properly on reconnect.
                val client = vdClient ?: return@launch
                if (!client.isConnected) {
                    if (client.acceptConnection(VirtualDisplayClient.SERVER_PORT)) {
                        FileLog.i(TAG, "VD server lifecycle connected (displayId=${client.displayId})")
                        InputInjectionService.instance?.setVirtualDisplay(client.displayId, vdWidth, vdHeight)
                        withContext(Dispatchers.Main) {
                            _serviceState.value = State.STREAMING
                            updateNotification(R.string.notification_streaming)
                        }
                        sendAppList()
                    } else {
                        FileLog.w(TAG, "VD server did not connect within timeout — tearing down")
                        withContext(Dispatchers.Main) { cleanupSession() }
                        return@launch
                    }
                }
            }
        }
    }

    // ─── VD Server Connection ───

    /**
     * Start the VD server process directly on the phone using Shizuku.
     *
     * Shizuku provides shell-level privileges so the phone can run app_process
     * without waiting for the car's USB ADB connection. The VD server will
     * reverse-connect to localhost:19647 as usual.
     */
    private suspend fun startVdServerViaShizuku(carWidth: Int, carHeight: Int, vdWidth: Int, vdHeight: Int, dpi: Int = VideoConfig.DEFAULT_FALLBACK_DPI) {
        if (!ShizukuManager.isAvailable) {
            FileLog.w(TAG, "Shizuku not available — cannot start VD server")
            return
        }
        try {
            val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto")
            if (!dir.exists()) dir.mkdirs()
            val jarPath = java.io.File(dir, "vd-server.jar").absolutePath
            val logFile = java.io.File(dir, "vd-server.log").absolutePath
            // Args: W H DPI PHONE_HOST EW EH FPS
            // VD binds 9638/9639 on 0.0.0.0, connects lifecycle to phoneHost:19647.
            // encode dims MUST be even (AVC encoder rejects odd width/height) — use the
            // already-aligned vdWidth/vdHeight for both VD and encode size.
            val args = "$vdWidth $vdHeight $dpi 127.0.0.1 $vdWidth $vdHeight $targetFps"

            ShizukuManager.execAndWait("pkill -f PipelineServer 2>/dev/null")
            delay(200)

            val cmd = "CLASSPATH=$jarPath exec app_process / " +
                    "com.dilinkauto.vdserver.PipelineServer $args" +
                    " >$logFile 2>&1"
            ShizukuManager.execBackground(cmd)
            FileLog.i(TAG, "VD server started via Shizuku: ${vdWidth}x$vdHeight @${dpi}dpi")
        } catch (e: Exception) {
            FileLog.e(TAG, "Shizuku VD server start failed", e)
        }
    }

    /**
     * Auto-update car app via dadb when handshake reveals outdated version.
     * Gets the car's IP from the WiFi gateway (car connects to phone's hotspot).
     */
    private fun autoUpdateCarApp(@Suppress("UNUSED_PARAMETER") conn: Connection) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                ensureAssetsReady()
                val apkFile = java.io.File(filesDir, "app-server.apk")
                if (!apkFile.exists()) {
                    FileLog.w(TAG, "Auto-update: car APK not found")
                    return@launch
                }

                // Get car IP from the active TCP connection (most reliable)
                val carIp = controlConnection?.remoteAddress
                    ?: CarIpLocator.findCarAdb(null)
                if (carIp == null) {
                    FileLog.w(TAG, "Auto-update: can't determine car IP")
                    return@launch
                }

                FileLog.i(TAG, "Auto-updating car app at $carIp:5555...")
                _installStatusStatic.value = getString(R.string.car_install_status_connecting_to, carIp)
                val dadb = carAppInstaller.connect(carIp)
                if (dadb == null) {
                    _installStatusStatic.value = getString(R.string.car_install_status_auth_needed)
                    autoUpdateFailedAt = System.currentTimeMillis()
                    return@launch
                }

                try {
                    val result = carAppInstaller.pushAndInstall(dadb, apkFile, /* versionLabel */ "")
                    FileLog.i(TAG, "Auto-update result: ${result.trim()}")
                    if (result.contains("Success")) {
                        _installStatusStatic.value = getString(R.string.status_auto_update_complete)
                        lastSentIconHash.clear() // car's icon cache was wiped by reinstall
                        FileLog.i(TAG, "Car app auto-updated — restarting")
                    } else {
                        _installStatusStatic.value = getString(R.string.status_update_failed, result.trim())
                        autoUpdateFailedAt = System.currentTimeMillis()
                        FileLog.w(TAG, "Auto-update failed: ${result.trim()} — will retry in 5min")
                    }
                } finally {
                    dadb.close()
                }
            } catch (e: Exception) {
                _installStatusStatic.value = getString(R.string.status_auto_update_failed, e.message ?: "unknown")
                autoUpdateFailedAt = System.currentTimeMillis()
                FileLog.e(TAG, "Auto-update failed: ${e.message} — will retry in 5min")
            }
        }
    }

    // ─── Data (from car) ───

    private fun handleDataFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            DataMsg.CAR_LOG -> {
                val line = String(frame.payload, Charsets.UTF_8)
                FileLog.i("CarLog", line)
            }
            DataMsg.NOTIFICATION_CLEAR -> {
                val msg = ClearNotificationMessage.decode(frame.payload)
                NotificationService.instance?.cancelNotification(msg.packageName, msg.id)
            }
            DataMsg.NOTIFICATION_CLEAR_ALL -> {
                NotificationService.instance?.cancelAll()
            }
        }
    }


    // ─── Car App Install via ADB ───

    private val _installStatus get() = _installStatusStatic

    fun installCarApp(explicitIp: String? = null) {
        serviceScope.launch(Dispatchers.IO) {
            var keepStatus = false
            try {
                ensureAssetsReady()
                val apkFile = java.io.File(filesDir, "app-server.apk")
                if (!apkFile.exists()) {
                    _installStatus.value = getString(R.string.car_install_status_car_apk_not_found)
                    FileLog.w(TAG, "No embedded car APK")
                    return@launch
                }

                _installStatus.value = if (explicitIp != null) getString(R.string.car_install_status_connecting_to, explicitIp) else getString(R.string.car_install_status_searching)
                val carIp = if (!explicitIp.isNullOrBlank()) {
                    if (CarIpLocator.probePortSync(explicitIp, 5555)) explicitIp else {
                        _installStatus.value = getString(R.string.car_install_status_not_reachable, explicitIp)
                        null
                    }
                } else CarIpLocator.findCarAdb(controlConnection?.remoteAddress)
                if (carIp == null) {
                    _installStatus.value = getString(R.string.car_install_status_car_not_found)
                    FileLog.w(TAG, "Could not find car ADB on USB or network")
                    return@launch
                }

                _installStatus.value = getString(R.string.car_install_status_connecting_to, carIp)
                FileLog.i(TAG, "Connecting to car ADB at $carIp:5555")
                val dadb = carAppInstaller.connect(carIp)
                if (dadb == null) {
                    _installStatus.value = getString(R.string.car_install_status_auth_needed)
                    keepStatus = true
                    return@launch
                }
                FileLog.d(TAG, "Dadb.create() succeeded")

                try {
                    _installStatus.value = getString(R.string.car_install_status_checking_version)
                    val installedVersionName = carAppInstaller.readInstalledVersion(dadb)
                    val myVersionName = packageManager.getPackageInfo(packageName, 0).let {
                        it.versionName ?: @Suppress("DEPRECATION") it.versionCode.toString()
                    }
                    FileLog.i(TAG, "Car app: installed=$installedVersionName, embedded=$myVersionName")

                    if (UpdateManager.compareVersions(myVersionName, installedVersionName) <= 0) {
                        _installStatus.value = getString(R.string.car_install_status_already_up_to_date, installedVersionName)
                        return@launch
                    }

                    val result = carAppInstaller.pushAndInstall(dadb, apkFile, myVersionName)
                    FileLog.i(TAG, "Install result: ${result.trim()}")

                    if (result.contains("Success")) {
                        lastSentIconHash.clear() // car's icon cache was wiped by reinstall
                        _installStatus.value = getString(R.string.car_install_status_car_installed, myVersionName)
                    } else {
                        _installStatus.value = getString(R.string.car_install_status_failed, result.trim())
                    }
                } finally {
                    dadb.close()
                }
            } catch (e: Exception) {
                _installStatus.value = getString(R.string.car_install_status_error, e.message ?: "unknown")
                FileLog.e(TAG, "Car app install failed", e)
            } finally {
                if (!keepStatus) {
                    delay(5000)
                    _installStatus.value = ""
                }
            }
        }
    }


    // ─── App List ───

    // Tracks the last icon hash sent per package — survives across reconnections
    // within the same service lifetime to avoid re-sending unchanged icons.
    private val lastSentIconHash = mutableMapOf<String, String>()

    private fun sendAppList() {
        val conn = controlConnection ?: return
        val pm = packageManager

        serviceScope.launch(Dispatchers.IO) {
            try {
                val apps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).filter { info ->
                    // Skip hidden apps (Xiaomi HyperOS, some custom ROMs disable
                    // the launcher component without removing the package)
                    val pkg = info.activityInfo.packageName
                    val cn = android.content.ComponentName(pkg, info.activityInfo.name)
                    val state = pm.getComponentEnabledSetting(cn)
                    state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }.map { info ->
                    val pkg = info.activityInfo.packageName
                    // Use lastUpdateTime as a lightweight change indicator.
                    // The car-side AppIconCache handles persistence, multi-size
                    // resizing, and in-memory Bitmap caching.
                    val hash = try {
                        pm.getPackageInfo(pkg, 0).lastUpdateTime.toString()
                    } catch (_: Exception) { "" }
                    // Only include icon data if the hash differs from last sent
                    val prevHash = lastSentIconHash[pkg]
                    val iconPng = if (hash.isNotEmpty() && hash == prevHash) {
                        ByteArray(0) // car can use its cached icon
                    } else {
                        lastSentIconHash[pkg] = hash
                        ClientApp.loadIconPng(pm, pkg, 192)
                    }
                    AppInfo(
                        pkg,
                        info.loadLabel(pm).toString(),
                        categorizeApp(pkg),
                        iconPng,
                        hash
                    )
                }.sortedBy { it.category.id }

                conn.sendData(DataMsg.APP_LIST, AppListMessage(apps).encode())
                val skipped = apps.count { it.iconPng.isEmpty() }
                FileLog.i(TAG, "App list sent: ${apps.size} apps (${skipped} icons skipped/unchanged)")
            } catch (e: Exception) {
                FileLog.e(TAG, "Failed to send app list", e)
            }
        }
    }

    // ─── App Shortcuts ───

    private fun sendAppInfoData(packageName: String) {
        val conn = controlConnection ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val pi = pm.getPackageInfo(packageName, 0)
                val ai = pm.getApplicationInfo(packageName, 0)
                val appName = pm.getApplicationLabel(ai).toString()
                val msg = AppInfoDataMessage(
                    packageName = packageName,
                    appName = appName,
                    versionName = pi.versionName ?: "",
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= 28)
                        pi.longVersionCode else pi.versionCode.toLong(),
                    installTime = pi.firstInstallTime,
                    targetSdk = pi.applicationInfo.targetSdkVersion
                )
                conn.sendData(DataMsg.APP_INFO_DATA, msg.encode())
                FileLog.i(TAG, "Sent app info for $packageName v${pi.versionName}")
            } catch (e: Exception) {
                FileLog.w(TAG, "Failed to query/send app info for $packageName: ${e.message}")
            }
        }
    }

    private fun sendAppShortcuts(packageName: String) {
        val conn = controlConnection ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                val shortcuts = queryShortcuts(packageName)
                val msg = AppShortcutsListMessage(packageName, shortcuts)
                conn.sendControl(ControlMsg.APP_SHORTCUTS_LIST, msg.encode())
                FileLog.i(TAG, "Sent ${shortcuts.size} shortcuts for $packageName")
            } catch (e: Exception) {
                FileLog.w(TAG, "Failed to query/send shortcuts for $packageName: ${e.message}")
                // Send empty list so car doesn't hang waiting
                try {
                    val msg = AppShortcutsListMessage(packageName, emptyList())
                    conn.sendControl(ControlMsg.APP_SHORTCUTS_LIST, msg.encode())
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun queryShortcuts(packageName: String): List<AppShortcut> {
        FileLog.i(TAG, "Querying shortcuts for $packageName: shizuku=${ShizukuManager.isAvailable} vdClient=${vdClient != null} vdConnected=${vdClient?.isConnected}")
        // True when Shizuku already proved cmd shortcut is unavailable on this device,
        // so we can skip the redundant VD server attempt (both run the same command).
        var cmdShortcutUnavailable = false
        // Try Shizuku shell first — has full access to shortcut data
        if (ShizukuManager.isAvailable) {
            try {
                val output = ShizukuManager.execAndWait("cmd shortcut get-shortcuts --package $packageName")
                if (!output.isNullOrEmpty()) {
                    val parsed = parseCmdShortcutOutput(output, packageName)
                    if (parsed.isNotEmpty()) {
                        FileLog.i(TAG, "Shizuku: ${parsed.size} shortcuts for $packageName")
                        return parsed
                    }
                    // cmd shortcut unavailable on this device — try dumpsys via Shizuku
                    cmdShortcutUnavailable = true
                    FileLog.d(TAG, "Shizuku: cmd shortcut returned ${output.length} chars but parsed empty, trying dumpsys")
                    val dumpOutput = ShizukuManager.execAndWait("dumpsys shortcut $packageName 2>&1")
                    if (!dumpOutput.isNullOrBlank()) {
                        val dumpParsed = parseCmdShortcutOutput(dumpOutput, packageName)
                        if (dumpParsed.isNotEmpty()) {
                            FileLog.i(TAG, "Shizuku dumpsys: ${dumpParsed.size} shortcuts for $packageName")
                            return dumpParsed
                        }
                    }
                    FileLog.i(TAG, "Shizuku: cmd shortcut unavailable, skipping VD server")
                }
            } catch (e: Exception) {
                FileLog.w(TAG, "Shizuku shortcut query failed for $packageName: ${e.message}")
            }
        }
        // Try VD server — skip if Shizuku already proved cmd shortcut is unavailable
        if (!cmdShortcutUnavailable) {
            val vd = vdClient
            if (vd != null && vd.isConnected) {
                FileLog.i(TAG, "VD server path: querying shortcuts for $packageName")
                try {
                    val output = vd.queryShortcuts(packageName)
                    if (!output.isNullOrBlank()) {
                        val parsed = parseCmdShortcutOutput(output, packageName)
                        if (parsed.isNotEmpty()) {
                            FileLog.i(TAG, "VD server returned ${parsed.size} shortcuts for $packageName")
                            return parsed
                        } else {
                            FileLog.w(TAG, "VD server returned output but parsed empty for $packageName")
                        }
                    } else {
                        FileLog.w(TAG, "VD server returned empty/null output for $packageName")
                    }
                } catch (e: Exception) {
                    FileLog.w(TAG, "VD shortcut query failed for $packageName: ${e.message}")
                }
            }
        }
        // Fallback: read shortcuts directly from the APK's XML resource.
        // Necessary when "cmd shortcut" service is unavailable (Samsung, Xiaomi, etc.)
        val apkShortcuts = queryShortcutsFromApkXml(packageName)
        if (apkShortcuts.isNotEmpty()) {
            FileLog.i(TAG, "APK XML: ${apkShortcuts.size} shortcuts for $packageName")
            return apkShortcuts
        }
        // Last resort: LauncherApps API (may fail with "Caller can't access shortcut information")
        return try {
            val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return emptyList()
            val user = android.os.Process.myUserHandle()
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            }
            (launcherApps.getShortcuts(query, user) ?: emptyList())
                .map { AppShortcut(it.id, it.shortLabel.toString(), it.longLabel.toString()) }
        } catch (e: Exception) {
            FileLog.w(TAG, "Shortcut query failed for $packageName: ${e.message}")
            emptyList()
        }
    }

    /** Parse output from 'cmd shortcut get-shortcuts' shell command. */
    private fun parseCmdShortcutOutput(output: String, expectedPackage: String): List<AppShortcut> {
        val shortcuts = mutableListOf<AppShortcut>()
        var currentId: String? = null
        var shortLabel = ""
        var longLabel = ""
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val isIndented = line.startsWith(" ") || line.startsWith("\t")

            // Package header line: "com.example.app:" (not indented)
            if (!isIndented && trimmed.endsWith(":")) {
                if (currentId != null) {
                    shortcuts.add(AppShortcut(currentId, shortLabel.ifEmpty { longLabel }, longLabel))
                }
                currentId = null; shortLabel = ""; longLabel = ""
                continue
            }
            // Shortcut id line (indented, ends with ":")
            if (isIndented && trimmed.endsWith(":") && !trimmed.contains(" ")) {
                if (currentId != null) {
                    shortcuts.add(AppShortcut(currentId, shortLabel.ifEmpty { longLabel }, longLabel))
                }
                currentId = trimmed.removeSuffix(":")
                shortLabel = ""; longLabel = ""
                continue
            }
            // Label lines
            if (currentId != null) {
                if (trimmed.startsWith("ShortLabel:")) {
                    shortLabel = trimmed.removePrefix("ShortLabel:").trim()
                } else if (trimmed.startsWith("LongLabel:")) {
                    longLabel = trimmed.removePrefix("LongLabel:").trim()
                }
            }
        }
        if (currentId != null) {
            shortcuts.add(AppShortcut(currentId, shortLabel.ifEmpty { longLabel }, longLabel))
        }
        return shortcuts
    }

    /**
     * Reads an app's shortcuts.xml resource directly from its APK using AssetManager.
     * This bypasses the ShortcutService entirely, working on devices where
     * "cmd shortcut" is unavailable (e.g. Samsung One UI).
     *
     * Shortcut labels in XML can be literal strings or resource references
     * (e.g. @string/wifi_label). We resolve references against the target
     * app's resources so labels display correctly.
     */
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun queryShortcutsFromApkXml(packageName: String): List<AppShortcut> {
        return try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            val shortcuts = mutableListOf<AppShortcut>()

            // Build an AssetManager pointing at the target APK
            val am = android.content.res.AssetManager::class.java.newInstance()
            val addPath = android.content.res.AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java).apply { isAccessible = true }
            val cookie = addPath.invoke(am, ai.publicSourceDir) as Int
            if (cookie == 0) return emptyList()

            val getResId = android.content.res.AssetManager::class.java
                .getDeclaredMethod("getResourceIdentifier", String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
            val resId = getResId.invoke(am, "shortcuts", "xml", ai.packageName) as Int
            if (resId == 0) return emptyList()

            val res = android.content.res.Resources(am, resources.displayMetrics, resources.configuration)

            // Try to get the target app's context to resolve its string resources
            val targetContext = try {
                createPackageContext(packageName, Context.CONTEXT_RESTRICTED)
            } catch (_: Exception) { null }
            val targetRes = targetContext?.resources

            val parser = res.getXml(resId)

            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "shortcut") {
                    val id = parser.getAttributeValue(null, "shortcutId")
                        ?: parser.getAttributeValue("http://schemas.android.com/apk/res/android", "shortcutId")
                    val rawLabel = parser.getAttributeValue(null, "shortcutShortLabel")
                        ?: parser.getAttributeValue("http://schemas.android.com/apk/res/android", "shortcutShortLabel")
                    val rawLongLabel = parser.getAttributeValue(null, "shortcutLongLabel")
                        ?: parser.getAttributeValue("http://schemas.android.com/apk/res/android", "shortcutLongLabel")

                    if (id != null) {
                        val displayLabel = resolveResourceRef(rawLabel, targetRes, ai) ?: id
                        val longLabel = resolveResourceRef(rawLongLabel, targetRes, ai) ?: displayLabel
                        shortcuts.add(AppShortcut(id, displayLabel, longLabel))
                    }
                }
                eventType = parser.nextToken()
            }
            parser.close()
            FileLog.i(TAG, "APK XML: ${shortcuts.size} shortcuts for $packageName")
            shortcuts
        } catch (e: Exception) {
            FileLog.w(TAG, "APK XML shortcut parse failed for $packageName: ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolves a possibly resource-referenced string value.
     * e.g. "@string/wifi_label" → "Wi-Fi", "@2131234567" → "Settings", "Wi-Fi" → "Wi-Fi".
     * Falls back to [targetRes] (the target package's resources), then to null.
     */
    private fun resolveResourceRef(value: String?, targetRes: android.content.res.Resources?, appInfo: android.content.pm.ApplicationInfo): String? {
        if (value.isNullOrEmpty()) return null
        // Already a literal string, not a reference
        if (!value.startsWith("@")) return value

        // Try target package resources first
        if (targetRes != null) {
            try {
                val resId = parseResourceRef(value, targetRes, appInfo.packageName)
                if (resId != 0) {
                    val resolved = targetRes.getString(resId)
                    if (resolved.isNotEmpty() && !resolved.startsWith("@")) return resolved
                }
            } catch (_: Exception) {}
        }
        // Couldn't resolve — return null so caller falls back to shortcutId
        return null
    }

    /** Parse a resource reference like "@string/wifi_label" or "@2131234567" to a resource ID. */
    private fun parseResourceRef(ref: String, res: android.content.res.Resources, pkg: String): Int {
        // Strip leading @
        val clean = ref.removePrefix("@")
        // Format: "type/name" or just numeric ID
        if (clean.startsWith("string/") || clean.startsWith("0x") || clean.all { it.isDigit() }) {
            val (type, name) = if (clean.contains("/")) {
                val parts = clean.split("/", limit = 2)
                parts[0] to parts[1]
            } else {
                // Numeric ID — convert to hex and try direct lookup
                val id = clean.toIntOrNull() ?: return 0
                return id
            }
            return res.getIdentifier(name, type, pkg)
        }
        return 0
    }

    // launchShortcut removed — shortcut execution now goes Car→VD directly via port 9639

    private fun categorizeApp(pkg: String): AppCategory = when {
        pkg.contains("map", true) || pkg.contains("navi", true) ||
        pkg.contains("waze", true) || pkg.contains("amap", true) ||
        pkg.contains("gaode", true) -> AppCategory.NAVIGATION

        pkg.contains("music", true) || pkg.contains("spotify", true) ||
        pkg.contains("podcast", true) || pkg.contains("player", true) ||
        pkg.contains("qqmusic", true) || pkg.contains("netease", true) -> AppCategory.MUSIC

        pkg.contains("whatsapp", true) || pkg.contains("telegram", true) ||
        pkg.contains("wechat", true) || pkg.contains("tencent.mm", true) ||
        pkg.contains("messenger", true) || pkg.contains("sms", true) ||
        pkg.contains("dialer", true) || pkg.contains("phone", true) -> AppCategory.COMMUNICATION

        else -> AppCategory.OTHER
    }

    // ─── Cleanup ───

    private fun cleanupSession() {
        handshakeJob?.cancel()
        handshakeJob = null
        vdWaitJob?.cancel()
        vdWaitJob = null
        vdClient?.stopVdServer()
        vdClient?.disconnect()
        vdClient = null
        InputInjectionService.instance?.clearVirtualDisplay()
        controlConnection?.disconnect()
        controlConnection = null
        activeConnection = null
        _serviceState.value = State.WAITING
        forceWakeScreen()
    }

    /**
     * Multi-layered display wake after disconnection. The VD server shuts off the
     * physical display at the SurfaceControl level (or via cmd display power-off),
     * which puts it in a deeper off state than normal screen timeout. Regular
     * WakeLocks can't recover from this — only system-level mechanisms can.
     *
     * Layers (tried in order, each is independent):
     * 1. PowerManager.wakeUp() via reflection — system-level wake
     * 2. FLAG_TURN_SCREEN_ON activity launch — WindowManager triggers display on
     * 3. WakeLock with ACQUIRE_CAUSES_WAKEUP — framework-level
     */
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun forceWakeScreen() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                FileLog.i(TAG, "Force-waking physical display")

                // Layer 0: Restore SurfaceFlinger-level display power via Shizuku.
                // The VD server powers off the physical panel directly via
                // DisplayControl.setDisplayPowerMode(0) — a deeper off than PowerManager
                // can recover from. Its own cleanup() only runs if the process exits
                // cleanly (CMD_STOP received). When the lifecycle channel breaks so
                // CMD_STOP never arrives, or the process hangs in a native futex,
                // cleanup() never runs and the panel stays off → phone is unusable.
                // PowerManager wakeUp/wake-locks cannot reverse this; only
                // setDisplayPowerMode(2) / "cmd display power-on" can, which needs
                // shell privileges (Shizuku). Kill the VD server first so it stops
                // re-powering-off the panel every second during touch injection.
                if (ShizukuManager.isAvailable) {
                    try {
                        ShizukuManager.execAndWait("pkill -9 -f PipelineServer 2>/dev/null")
                        delay(150)
                        ShizukuManager.execAndWait("cmd display power-on 0 2>/dev/null")
                        val targetIme = savedDefaultIme
                            ?: getSharedPreferences("dilinkauto", MODE_PRIVATE).getString("saved_default_ime", null)
                        if (!targetIme.isNullOrBlank() && targetIme != "null" && !targetIme.contains("linkpc", ignoreCase = true)) {
                            ShizukuManager.execAndWait("ime enable $targetIme; ime set $targetIme; settings put secure default_input_method $targetIme 2>/dev/null")
                            FileLog.i(TAG, "Original IME restored via Shizuku: $targetIme")
                        }
                        savedDefaultIme = null
                        FileLog.i(TAG, "Physical display restored via Shizuku (pkill + power-on + IME)")
                    } catch (e: Exception) {
                        FileLog.w(TAG, "Shizuku display restore failed: ${e.message}")
                    }
                }

                val pm = getSystemService(POWER_SERVICE) as PowerManager

                // Layer 1: PowerManager.wakeUp() — direct system call
                try {
                    val wakeUp = PowerManager::class.java.getDeclaredMethod(
                        "wakeUp", Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, String::class.java
                    )
                    wakeUp.invoke(pm, android.os.SystemClock.uptimeMillis(),
                        5 /* WAKE_REASON_APPLICATION */, "DiLink:restore")
                    FileLog.i(TAG, "Display wakeUp() succeeded from cleanupSession")
                } catch (e: Exception) {
                    FileLog.d(TAG, "wakeUp() not available from cleanupSession: ${e.message}")
                }

                // Layer 2: Launch MainActivity with FLAG_TURN_SCREEN_ON.
                // WindowManager wakes the display as part of bringing the
                // activity to the foreground, regardless of the display's
                // current power state.
                try {
                    val intent = Intent(this@ConnectionService, Class.forName("com.dilinkauto.client.MainActivity"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    intent.addFlags(0x10000000) // FLAG_TURN_SCREEN_ON
                    startActivity(intent)
                    FileLog.i(TAG, "Launched MainActivity with FLAG_TURN_SCREEN_ON from cleanupSession")
                } catch (e: Exception) {
                    FileLog.d(TAG, "Activity launch for wake failed from cleanupSession: ${e.message}")
                }

                // Layer 3: WakeLock with ACQUIRE_CAUSES_WAKEUP
                @Suppress("DEPRECATION")
                val flags = android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE
                val wl = pm.newWakeLock(flags, "DiLink:display:restore2")
                wl.acquire(3000)
                wl.release()
            } catch (e: Exception) {
                FileLog.w(TAG, "forceWakeScreen error: ${e.message}")
            }
        }
    }

    private fun stopEverything() {
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        cleanupSession()
        lastSentIconHash.clear()
        serviceRegistration?.unregister()
        serviceRegistration = null
        _serviceState.value = State.IDLE
    }

    // ─── System ───

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DiLinkAuto::ConnectionService"
        ).apply { acquire(4 * 60 * 60 * 1000L) } // 4h auto-release
    }

    private fun buildNotification(messageRes: Int): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, ConnectionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ClientApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(messageRes))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_action_stop), stopPi)
            .build()
    }

    private fun updateNotification(messageRes: Int) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(messageRes))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopEverything()
        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
        packageRemovedReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        packageRemovedReceiver = null
        wakeLock?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ConnectionService"
        private const val VD_SERVER_PORT = 19647
        const val ACTION_START = "com.dilinkauto.client.START"
        const val ACTION_STOP = "com.dilinkauto.client.STOP"
        const val ACTION_INSTALL_CAR = "com.dilinkauto.client.INSTALL_CAR"
        const val NOTIFICATION_ID = 1001

        /** Propagate log toggle to car. Called from settings UI and onCreate. */
        fun setLogEnabled(context: android.content.Context, enabled: Boolean) {
            FileLog.enabled = enabled
            // Persist both the value and the fact that user explicitly set it
            context.getSharedPreferences("dilinkauto", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("log_enabled", enabled)
                .putBoolean("log_enabled_user_set", true).apply()
            val conn = activeConnection
            if (conn != null && conn.isConnected) {
                try { conn.sendData(DataMsg.LOG_TOGGLE, byteArrayOf(if (enabled) 1 else 0)) }
                catch (_: Exception) {}
            }
        }

        private val _serviceState = MutableStateFlow(State.IDLE)
        val serviceState: StateFlow<State> = _serviceState.asStateFlow()

        private val _installStatusStatic = MutableStateFlow("")
        val installStatusFlow: StateFlow<String> = _installStatusStatic.asStateFlow()

        var activeConnection: Connection? = null
            private set
    }
}

