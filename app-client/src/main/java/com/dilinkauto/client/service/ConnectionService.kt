package com.dilinkauto.client.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dilinkauto.client.ClientApp
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.client.display.VirtualDisplayClient
import com.dilinkauto.protocol.*
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
    private lateinit var appListBuilder: AppListBuilder
    private lateinit var displayRestorer: PhoneDisplayRestorer

    enum class State { IDLE, WAITING, CONNECTED, STREAMING }

    override fun onBind(intent: Intent?): IBinder? = null

    private var packageRemovedReceiver: BroadcastReceiver? = null
    @Volatile private var savedDefaultIme: String? = null

    override fun onCreate() {
        super.onCreate()
        FileLog.loadEnabled(getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE))
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
        appListBuilder = AppListBuilder(applicationContext, serviceScope)
        displayRestorer = PhoneDisplayRestorer(applicationContext, serviceScope)
    }

    private fun cacheDefaultIme() {
        try {
            val currentIme = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!currentIme.isNullOrBlank() && currentIme != "null" && !currentIme.contains("linkpc", ignoreCase = true)) {
                savedDefaultIme = currentIme
                getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE).edit().putString(AppPrefs.SAVED_DEFAULT_IME, currentIme).apply()
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
                        appListBuilder.sendAppList(conn)
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
            val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), VdDeploy.DIR_PATH)
            dir.mkdirs()
            extractAsset(VdDeploy.JAR_NAME, java.io.File(dir, VdDeploy.JAR_NAME))
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
            ACTION_ALLOWLIST_UPDATED -> {
                // Allowlist screen changed the selection — re-send so the car grid updates live.
                appListBuilder.sendAppList(controlConnection)
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
            // LAUNCH_APP, GO_BACK, GO_HOME, APP_UNINSTALL, APP_INFO
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

        // Create VD at car viewport size. The DPI/size computation (anti-crop
        // scale for Chinese-ROM IME hardcoding + DPI override vs. auto) lives
        // in VdDimensions; see it for the rationale.
        val dm = resources.displayMetrics
        val (vdWidth, vdHeight, displayDpi) = VdDimensions.compute(request, dm)
        FileLog.i(TAG, "VD: ${vdWidth}x${vdHeight} @${displayDpi}dpi (car reported ${request.screenDpi}dpi, override=${request.dpiOverride}, auto-calibrated optimal touch scale)")

        // Open lifecycle channel if not already open (survives re-handshakes)
        if (vdClient == null) {
            val lifecycleClient = VirtualDisplayClient(serviceScope, this@ConnectionService)
            lifecycleClient.onStackEmpty = {
            val c = controlConnection
            if (c?.isConnected == true) {
                try { c.sendControl(ControlMsg.VD_STACK_EMPTY) } catch (_: Exception) {}
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
            adbPort = Discovery.ADB_PORT,
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
            val myVersionName = AppVersion.label(this@ConnectionService, preferCode = !carHasSemver)
            val updateCooldown = autoUpdateFailedAt > 0L &&
                System.currentTimeMillis() - autoUpdateFailedAt < 5 * 60 * 1000L
            val needsUpdate = compareVersions(myVersionName, carVersionName) > 0
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

                if (compareVersions(myVersionName, carVersionName) > 0) {
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
                        appListBuilder.sendAppList(conn)
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
            val dir = java.io.File(android.os.Environment.getExternalStorageDirectory(), VdDeploy.DIR_PATH)
            if (!dir.exists()) dir.mkdirs()
            val jarPath = java.io.File(dir, VdDeploy.JAR_NAME).absolutePath
            val logPath = java.io.File(dir, VdDeploy.LOG_NAME).absolutePath
            // VD dims (vdWidth/vdHeight) are the scaled-up values (preserves the
            // IME-crop fix for Chinese ROMs that hardcode IME width to phone
            // physical width). Encode dims (carWidth/carHeight) are the car-native
            // viewport clamped to 1920x1080: the Snapdragon 439 VPU caps hardware
            // AVC decode at 1080p. Encoding larger forces software decode on the
            // car's 8x A53 (single-digit fps). Car-native is also 1:1 with the car's
            // pixels, so no downscale on decode.
            val args = VdDeployArgs.format(vdWidth, vdHeight, dpi, "127.0.0.1", carWidth, carHeight, targetFps)

            ShizukuManager.execAndWait(VdDeploy.killCommand)
            delay(200)

            val cmd = VdDeploy.commandLine(jarPath, logPath, args, background = false)
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

                FileLog.i(TAG, "Auto-updating car app at $carIp:${Discovery.ADB_PORT}...")
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
                        appListBuilder.resetIconHashes() // car's icon cache was wiped by reinstall
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
                    if (CarIpLocator.probePortSync(explicitIp, Discovery.ADB_PORT)) explicitIp else {
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
                FileLog.i(TAG, "Connecting to car ADB at $carIp:${Discovery.ADB_PORT}")
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
                    val myVersionName = AppVersion.label(this@ConnectionService)
                    FileLog.i(TAG, "Car app: installed=$installedVersionName, embedded=$myVersionName")

                    if (compareVersions(myVersionName, installedVersionName) <= 0) {
                        _installStatus.value = getString(R.string.car_install_status_already_up_to_date, installedVersionName)
                        return@launch
                    }

                    val result = carAppInstaller.pushAndInstall(dadb, apkFile, myVersionName)
                    FileLog.i(TAG, "Install result: ${result.trim()}")

                    if (result.contains("Success")) {
                        appListBuilder.resetIconHashes() // car's icon cache was wiped by reinstall
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
        appListBuilder.resetIconHashes() // Force resend icons on reconnect
        _serviceState.value = State.WAITING
        val ime = savedDefaultIme
        savedDefaultIme = null
        displayRestorer.restore(ime)
    }

    private fun stopEverything() {
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        cleanupSession()
        appListBuilder.resetIconHashes()
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
        const val ACTION_START = "com.dilinkauto.client.START"
        const val ACTION_STOP = "com.dilinkauto.client.STOP"
        const val ACTION_INSTALL_CAR = "com.dilinkauto.client.INSTALL_CAR"
        const val ACTION_ALLOWLIST_UPDATED = "com.dilinkauto.client.ALLOWLIST_UPDATED"
        const val ALLOWLIST_PREFS = "dilinkauto_allowlist"
        const val ALLOWLIST_PACKAGES_KEY = "allowed_packages"
        const val ALLOWLIST_CONFIGURED_KEY = "allowlist_configured"
        const val NOTIFICATION_ID = 1001

        /** Propagate log toggle to car. Called from settings UI and onCreate. */
        fun setLogEnabled(context: android.content.Context, enabled: Boolean) {
            FileLog.enabled = enabled
            // Persist both the value and the fact that user explicitly set it
            context.getSharedPreferences(AppPrefs.FILE_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(AppPrefs.LOG_ENABLED, enabled)
                .putBoolean(AppPrefs.LOG_ENABLED_USER_SET, true).apply()
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

