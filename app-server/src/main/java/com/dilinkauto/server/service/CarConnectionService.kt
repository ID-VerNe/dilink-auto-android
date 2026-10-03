package com.dilinkauto.server.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dilinkauto.protocol.*
import com.dilinkauto.server.R
import com.dilinkauto.server.ServerApp
import com.dilinkauto.server.CarCrashHandler
import com.dilinkauto.server.adb.RemoteAdbController
import com.dilinkauto.protocol.adb.UsbAdbConnection
import com.dilinkauto.server.decoder.VideoDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Car-side service managing the connection to the phone.
 *
 * State machine with parallel prerequisites:
 *   IDLE → CONNECTING → CONNECTED → STREAMING
 *
 * CONNECTING runs two independent tracks in parallel:
 *   Track A (WiFi): discovery → TCP connect → handshake → wifiReady=true
 *   Track B (USB):  detect device → USB ADB connect → usbReady=true
 *
 * When BOTH tracks complete → CONNECTED → deploy VD server
 * When video frames arrive → STREAMING
 *
 * checkAndAdvance() is called whenever any prerequisite changes.
 */
class CarConnectionService : Service() {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var controlConnection: Connection? = null
    @Volatile private var videoConnection: Connection? = null
    @Volatile private var inputConnection: Connection? = null
    val videoDecoder = VideoDecoder()
    @Volatile internal var adbController: RemoteAdbController? = null
    @Volatile internal var phoneHost: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var consecutiveFailures = 0
    private var usbAdb: UsbAdbConnection? = null
    private var userDisconnected: Boolean
        get() = getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE)
            .getBoolean("user_disconnected", false)
        set(value) = getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE)
            .edit().putBoolean("user_disconnected", value).apply()

    var devMode: Boolean
        get() = getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE)
                    .getBoolean("dev_mode", false)
        set(value) = getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE)
            .edit().putBoolean("dev_mode", value).apply()

    /**
     * Car-side startup DPI override. 0 = auto-calibrate via VideoConfig.calculateOptimalDpi
     * (the portrait-app-safe cap, default). Non-zero in [120, 480] bypasses the cap and is
     * sent to the phone as `dpiOverride` in HandshakeRequest; the phone uses it verbatim and
     * echoes it back as `vdDpi`. Read at handshake construction time, so a change takes effect
     * on the next connect (or mid-stream rotation re-handshake), not live.
     */
    var startupDpi: Int
        get() = getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE)
                    .getInt("startup_dpi", 0)
        set(value) = getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE)
            .edit().putInt("startup_dpi", value).apply()

    // ─── Handshake ───
    internal var handshakeVdDpi = VideoConfig.VIRTUAL_DISPLAY_DPI // DPI from phone (may be adjusted for DeX)

    internal var vdServerJarPath = VdDeploy.JAR_PATH

    internal val vdDeployer = VdServerDeployer(this)

    private val touchSender = CarTouchSender(
        inputConnectionProvider = { inputConnection },
        stateProvider = { _state.value },
        log = { msg, level -> carLogSend(msg, level) }
    )

    private val logWriter = CarLogWriter(TAG) { controlConnection }

    // ─── Parallel prerequisites ───
    @Volatile private var wifiReady = false       // WiFi TCP handshake completed
    @Volatile private var usbReady = false        // USB ADB connected to phone
    private var connectionScope: Job? = null  // Parent job for all discovery/connect coroutines
    @Volatile internal var vdServerStarted = false // VD server process launched
    @Volatile private var updatingFromPhone = false // Phone is pushing an update — don't reconnect
    @Volatile private var shizukuMode = false  // Phone handles VD server via Shizuku
    @Volatile private var handshakeDone = false // Stop gateway retry after handshake completes
    @Volatile private var lastAdbHost: String? = null // Track which host TCP ADB connected to
    internal var noAdbCount = 0 // Consecutive deploy failures due to no ADB — stops reconnect loop
    @Volatile private var tcpAdbConnecting = false // Prevent duplicate TCP ADB attempts

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val usbPermissionAction = "com.dilinkauto.server.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) onUsbDeviceAttached(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    carLogSend("USB device detached")
                    usbAdb?.close()
                    usbAdb = null
                    usbReady = false
                }
                usbPermissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && device != null) {
                        carLogSend("USB permission granted")
                        connectUsbAdb(device)
                    } else {
                        carLogSend("USB permission denied")
                    }
                }
            }
        }
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _phoneName = MutableStateFlow("")
    val phoneName: StateFlow<String> = _phoneName.asStateFlow()

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList.asStateFlow()

    private val _mediaMetadata = MutableStateFlow<MediaMetadata?>(null)
    val mediaMetadata: StateFlow<MediaMetadata?> = _mediaMetadata.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState?>(null)
    val playbackState: StateFlow<PlaybackState?> = _playbackState.asStateFlow()

    private val _vdStackEmpty = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val vdStackEmpty: SharedFlow<Unit> = _vdStackEmpty.asSharedFlow()

    private val _videoReady = MutableStateFlow(false)
    val videoReady: StateFlow<Boolean> = _videoReady.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _appInfoData = MutableStateFlow<AppInfoDataMessage?>(null)
    val appInfoData: StateFlow<AppInfoDataMessage?> = _appInfoData.asStateFlow()

    enum class State { IDLE, CONNECTING, CONNECTED, STREAMING }

    inner class LocalBinder : Binder() {
        val service: CarConnectionService get() = this@CarConnectionService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ─── Lifecycle ───

    override fun onCreate() {
        super.onCreate()
        logWriter.setEnabled(com.dilinkauto.server.BuildConfig.DEBUG)
        logWriter.start()
        videoDecoder.logSink = { msg -> carLogSend(msg) }

        // Wire crash handler to TCP log sink for immediate crash delivery
        CarCrashHandler.logSink = { msg -> carLogSend(msg) }

        // Log device info for diagnostics
        carLogSend(CarCrashHandler.buildDeviceInfo(this))

        // Send any crash report from the previous run
        val crash = CarCrashHandler.consumePendingCrash()
        if (crash != null) {
            carLogSend("──── PREVIOUS CRASH REPORT ────", "E")
            crash.lines().forEach { carLogSend(it, "E") }
            carLogSend("──── END CRASH REPORT ────", "E")
        }

        acquireWakeLock()
        registerNetworkCallback()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(usbPermissionAction)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                carLogSend("WiFi network available — retrying WiFi track if needed")
                if (_state.value == State.CONNECTING && !wifiReady) {
                    startWifiTrack()
                }
            }
        }
        cm.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_searching))
                startConnection()
            }
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, Discovery.DEFAULT_PORT)
                startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_searching))
                userDisconnected = false
                _state.value = State.CONNECTING
                connectToPhone(host, port)
            }
            ACTION_STOP -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun connectManual(host: String, port: Int = Discovery.DEFAULT_PORT) {
        userDisconnected = false
        _state.value = State.CONNECTING
        connectToPhone(host, port)
        startUsbTrack()  // dev mode TCP ADB or USB ADB
    }

    // ─── State Machine Core ───

    /**
     * Start both tracks in parallel. Called on ACTION_START, reconnect, etc.
     */
    private fun startConnection() {
        if (_state.value == State.STREAMING || _state.value == State.CONNECTED) return

        // Cancel ALL previous connection work before starting fresh
        connectionScope?.cancel()
        connectJob?.cancel()
        connectJob = null
        disconnectAllConnections()

        // Reset prerequisite flags (keep usbReady if USB is physically connected)
        wifiReady = false
        vdServerStarted = false
        shizukuMode = false
        vdDeployer.reset()
        handshakeDone = false
        _videoReady.value = false
        if (usbAdb?.isConnected != true) {
            usbReady = false
            if (usbAdb == null) usbConnecting = false // only reset if no ADB instance (auth may be pending)
        }

        logWriter.setEnabled(com.dilinkauto.server.BuildConfig.DEBUG)  // Reset to default each session
        userDisconnected = false
        _state.value = State.CONNECTING
        _statusMessage.value = getString(R.string.status_connecting)
        updateNotification(R.string.notification_searching)

        // Launch both tracks under a single parent job — cancelling connectionScope
        // kills all child coroutines (discovery, mDNS, USB scan)
        connectionScope = scope.launch {
            startWifiTrack()
            startUsbTrack()
        }
    }

    /**
     * Central state advancement. Called whenever any prerequisite changes.
     * Evaluates current state and advances if all conditions are met.
     */
    @Synchronized
    private fun checkAndAdvance() {
        val currentState = _state.value
        carLogSend("checkAndAdvance: state=$currentState wifi=$wifiReady usb=$usbReady vd=$vdServerStarted video=${_videoReady.value}")

        when (currentState) {
            State.IDLE -> { /* waiting for user action */ }

            State.CONNECTING -> {
                // VD server is deployed immediately after handshake (by car via ADB or
                // by phone via Shizuku). wifiReady is set after video/input connections
                // are established following VD_PORTS_BOUND.
                if (wifiReady && (usbReady || shizukuMode)) {
                    carLogSend("All connections ready — connected")
                    _state.value = State.CONNECTED
                    _statusMessage.value = getString(R.string.status_waiting_video)
                } else if (wifiReady && !usbReady && !shizukuMode) {
                    _statusMessage.value = getString(R.string.status_waiting_usb)
                } else if (!wifiReady && usbReady) {
                    _statusMessage.value = getString(R.string.status_waiting_wifi)
                } else if (!wifiReady && !usbReady) {
                    _statusMessage.value = getString(R.string.status_connecting)
                }
            }

            State.CONNECTED -> {
                if (_videoReady.value) {
                    carLogSend("Video ready — streaming")
                    _state.value = State.STREAMING
                    consecutiveFailures = 0
                }
            }

            State.STREAMING -> { /* running */ }
        }
    }

    // ─── Track A: WiFi ───

    private fun startWifiTrack() {
        // Strategy 1: Gateway IP retry loop (phone is hotspot)
        // Retries every 3s until wifiReady or handshakeDone — handles hotspot enabled after USB plug
        // and Shizuku mode where phone's service may not be listening on first attempt
        scope.launch(Dispatchers.IO) {
            delay(500)
            while (isActive && !wifiReady && !handshakeDone && _state.value == State.CONNECTING) {
                val gatewayIp = getWifiGatewayIp()
                if (gatewayIp != null) {
                    carLogSend("WiFi track: trying gateway $gatewayIp")
                    connectToPhone(gatewayIp, Discovery.DEFAULT_PORT)
                }
                delay(3000)
            }
        }

        // Strategy 2: mDNS (runs continuously until wifiReady or handshakeDone)
        scope.launch {
            Discovery.discoverServices(this@CarConnectionService).collect { service ->
                if (!wifiReady && !handshakeDone && _state.value == State.CONNECTING) {
                    carLogSend("WiFi track: mDNS found ${service.host}")
                    connectToPhone(service.host, service.port)
                }
            }
        }
    }

    private var connectJob: Job? = null

    private fun connectToPhone(host: String, port: Int) {
        // Don't kill an active session — if we already have a working control
        // connection with handshake completed, any new connect attempt is a race.
        if (handshakeDone && controlConnection?.isConnected == true) {
            carLogSend("connectToPhone: session active, skipping ($host:$port)")
            return
        }

        // Close old connections BEFORE starting new ones — prevents stale callbacks
        connectJob?.cancel()
        disconnectAllConnections()

        connectJob = scope.launch(Dispatchers.IO) {
            try {
                // ─── Step 1: Connect control connection (port 9637) ───
                val ctrl = Connection.connect(host, port, scope)
                controlConnection = ctrl
                phoneHost = host

                ctrl.onLog { msg -> carLogSend("ControlConn: $msg") }
                ctrl.onDisconnect { scope.launch { handleDisconnect() } }
                ctrl.onFrames(Channel.CONTROL) { handleControlFrame(it) }
                ctrl.onFrames(Channel.DATA) { handleDataFrame(it) }

                ctrl.start() // heartbeat enabled (default)
                carLogSend("Control connected to $host:$port — sending handshake")

                val displayMetrics = resources.displayMetrics
                val vp = getViewportSize(displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.density)
                val viewportWidth = vp.first
                val viewportHeight = vp.second
                val handshake = buildHandshakeRequest(
                    context = this@CarConnectionService,
                    screenWidth = viewportWidth,
                    screenHeight = viewportHeight,
                    screenDpi = displayMetrics.densityDpi,
                    targetFps = targetFps,
                    dpiOverride = startupDpi
                )
                ctrl.sendControl(ControlMsg.HANDSHAKE_REQUEST, handshake.encode())
                handshakeDone = true  // Stop gateway/mDNS retry loops immediately

                withContext(Dispatchers.Main) { updateNotification(R.string.notification_connected) }

                // Wait for VD_PORTS_BOUND → connectVideoAndInput → wifiReady.
                // The watchdog on control connection handles dead connections.
                // No timeout — VD deployment and port binding can take several seconds.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                carLogSend("WiFi connect failed: ${e.message}")
                handleDisconnect()
            }
        }
    }

    /**
     * Opens video and input connections after handshake succeeds.
     * Called from handleControlFrame when HANDSHAKE_RESPONSE is received.
     */
    private fun connectVideoAndInput(host: String) {
        if (updatingFromPhone) {
            carLogSend("Skipping video/input connections — update in progress")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (updatingFromPhone) {
                    carLogSend("Skipping video/input connections — update flag set during launch")
                    return@launch
                }
                carLogSend("Connecting video (${Discovery.VIDEO_PORT}) and input (${Discovery.INPUT_PORT})...")

                val videoDef = async { Connection.connect(host, Discovery.VIDEO_PORT, scope) }
                val inputDef = async { Connection.connect(host, Discovery.INPUT_PORT, scope) }

                val video = videoDef.await()
                videoConnection = video
                video.onLog { msg -> carLogSend("VideoConn: $msg") }
                video.onDisconnect { scope.launch { handleDisconnect() } }
                video.onFrames(Channel.VIDEO) { handleVideoFrame(it) }
                video.start(enableHeartbeat = false)
                carLogSend("Video connection established")

                val input = inputDef.await()
                inputConnection = input
                input.onLog { msg -> carLogSend("InputConn: $msg") }
                input.onDisconnect { scope.launch { handleDisconnect() } }
                input.start(enableHeartbeat = false)
                carLogSend("Input connection established — all 3 connections ready")

                wifiReady = true
                checkAndAdvance()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                carLogSend("Failed to connect video/input: ${e.message}")
                handleDisconnect()
            }
        }
    }

    private fun disconnectAllConnections() {
        videoConnection?.disconnect()
        videoConnection = null
        inputConnection?.disconnect()
        inputConnection = null
        controlConnection?.disconnect()
        controlConnection = null
    }

    // ─── Track B: USB ───

    private fun startUsbTrack() {
        if (usbReady) return // Already connected
        if (devMode) {
            carLogSend("Development mode active — using TCP ADB instead of USB")
            startTcpAdbTrack()
            return
        }
        scope.launch(Dispatchers.IO) {
            // Scan for already-connected USB device
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            for (device in usbManager.deviceList.values) {
                if (UsbAdbConnection.findAdbInterface(device) != null) {
                    carLogSend("USB track: found existing device ${device.productName}")
                    withContext(Dispatchers.Main) { onUsbDeviceAttached(device) }
                    return@launch
                }
            }
            carLogSend("USB track: no device found, waiting for attach broadcast")
        }
    }

    // ─── Dev mode: TCP ADB track (replaces USB track) ───

    private fun startTcpAdbTrack() {
        if (tcpAdbConnecting) return
        // Reconnect if phone IP changed since last ADB connection
        if (adbController?.isConnected == true && lastAdbHost == phoneHost) return
        if (adbController?.isConnected == true && lastAdbHost != phoneHost && phoneHost != null) {
            carLogSend("Dev mode: phone IP changed $lastAdbHost -> $phoneHost, reconnecting TCP ADB")
            adbController?.disconnect()
            adbController = null
            lastAdbHost = null
        }
        tcpAdbConnecting = true
        scope.launch(Dispatchers.IO) {
            try {
                var attempts = 0
                while (isActive && !usbReady && _state.value == State.CONNECTING && attempts < 60) {
                    val host = phoneHost
                    if (host != null) {
                        carLogSend("Dev mode: TCP ADB connecting to $host:${Discovery.ADB_PORT} (attempt ${attempts + 1})")
                        connectTcpAdb(host)
                        if (adbController?.isConnected == true) {
                            lastAdbHost = host
                        }
                        return@launch
                    }
                    delay(1000)
                    attempts++
                }
                if (!usbReady) {
                    carLogSend("Dev mode: could not determine phone IP after ${attempts}s")
                }
            } finally {
                tcpAdbConnecting = false
            }
        }
    }

    internal suspend fun connectTcpAdb(host: String) {
        if (usbReady) return
        if (adbController?.isConnected == true) {
            carLogSend("Dev mode: TCP ADB already connected, skipping duplicate")
            return
        }

        _statusMessage.value = getString(R.string.status_connecting_tcp_adb, host)
        val keyDir = java.io.File(filesDir, "adb_keys")
        val controller = RemoteAdbController(
            phoneHost = host,
            adbPort = Discovery.ADB_PORT,
            virtualDisplayId = -1,
            keyDir = keyDir
        )

        if (!controller.connect()) {
            _statusMessage.value = getString(R.string.status_tcp_adb_failed)
            carLogSend("Dev mode: TCP ADB connection failed to $host:${Discovery.ADB_PORT}")
            return
        }

        adbController = controller
        _statusMessage.value = getString(R.string.status_tcp_adb_connected)
        carLogSend("Dev mode: TCP ADB connected to $host:${Discovery.ADB_PORT}")

        usbReady = true
        noAdbCount = 0  // Reset — ADB is available now

        // Deploy VD server IMMEDIATELY using the same Dadb connection
        if (handshakeDone && !vdServerStarted) {
            carLogSend("TCP ADB ready — deploying VD server immediately")
            deployVdServerDirect(controller)
        }
        // Launch phone app after VD server
        try { controller.shell("am start -n ${AppTargets.PHONE_MAIN_ACTIVITY}") } catch (_: Exception) {}
        carLogSend("Dev mode: phone app launched via TCP ADB")

        checkAndAdvance()
    }

    internal fun isAdbAvailable(): Boolean {
        val tcpOk = adbController?.isConnected == true
        val usbOk = usbAdb?.isConnected == true
        if (!tcpOk && !usbOk) {
            carLogSend("isAdbAvailable: tcp=${adbController != null}.${adbController?.isConnected} usb=${usbAdb != null}.${usbAdb?.isConnected}")
        }
        return tcpOk || usbOk
    }

    /** Route to the deployer; kept on the service so callers (state machine, USB/TCP tracks) don't change. */
    internal fun deployVdServer() = vdDeployer.deploy()

    internal fun executeAdb(command: String, noWait: Boolean): Boolean {
        return when {
            adbController?.isConnected == true -> {
                if (noWait) adbController!!.shellNoWait(command)
                else adbController!!.shell(command)
            }
            usbAdb?.isConnected == true -> {
                if (noWait) usbAdb!!.shellNoWait(command) >= 0
                else { usbAdb!!.shell(command); true }
            }
            else -> false
        }
    }

    private fun onUsbDeviceAttached(device: UsbDevice) {
        if (UsbAdbConnection.findAdbInterface(device) == null) return
        userDisconnected = false

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            carLogSend("USB device with permission: ${device.productName}")
            connectUsbAdb(device)
        } else {
            carLogSend("Requesting USB permission for: ${device.productName}")
            val pi = PendingIntent.getBroadcast(this, 0,
                Intent(usbPermissionAction), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
        }
    }

    @Volatile private var usbConnecting = false

    private fun connectUsbAdb(device: UsbDevice) {
        if (usbReady || usbConnecting) {
            carLogSend("USB ADB already ${if (usbReady) "connected" else "connecting"} — skipping")
            return
        }
        usbConnecting = true
        scope.launch(Dispatchers.IO) {
            _statusMessage.value = getString(R.string.status_connecting_usb)
            val adb = UsbAdbConnection(this@CarConnectionService)
            adb.setLogSink { msg -> carLogSend(msg) }
            if (!adb.connect(device)) {
                usbConnecting = false
                _statusMessage.value = getString(R.string.status_usb_failed)
                carLogSend("USB ADB connection failed")
                return@launch
            }
            usbAdb = adb
            _statusMessage.value = getString(R.string.status_usb_connected)
            carLogSend("USB ADB connected")
            noAdbCount = 0  // Reset — ADB is available now

            // Write key diagnostic to phone for debugging USB auth issues
            val keyInfo = adb.keyDiagnostic()
            adb.shell("echo '$keyInfo' > /data/local/tmp/car-adb-key.log")
            carLogSend("ADB key: $keyInfo")

            // Launch phone app (don't clear task — if it's already open, just move on)
            adb.shell("am start -n ${AppTargets.PHONE_MAIN_ACTIVITY}")
            carLogSend("Phone app launched via USB ADB")

            usbConnecting = false
            usbReady = true

            if (handshakeDone && !vdServerStarted) {
                carLogSend("USB ADB ready after handshake — deploying VD server")
                deployVdServer()
            }

            // If we're not in a connecting flow yet, start one
            if (_state.value == State.IDLE) {
                withContext(Dispatchers.Main) { startConnection() }
            } else {
                checkAndAdvance()
            }
        }
    }

    fun onUsbDeviceFromActivity(device: UsbDevice) {
        onUsbDeviceAttached(device)
    }

    // ─── Frame Handlers ───

    private fun handleControlFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            ControlMsg.HANDSHAKE_RESPONSE -> {
                val response = HandshakeResponse.decode(frame.payload)
                carLogSend("Handshake OK: ${response.deviceName} jarPath=${response.vdServerJarPath} connMethod=${response.connectionMethod} vdDpi=${response.vdDpi}")
                _phoneName.value = response.deviceName
                vdWidth = response.displayWidth
                vdHeight = response.displayHeight
                handshakeVdDpi = response.vdDpi
                if (response.vdServerJarPath.isNotEmpty()) {
                    vdServerJarPath = response.vdServerJarPath
                }

                handshakeDone = true  // Stop WiFi gateway retry loop
                if (response.connectionMethod == CONNECTION_METHOD_SHIZUKU) {
                    shizukuMode = true
                    carLogSend("Shizuku mode — phone will deploy VD server, waiting for VD_PORTS_BOUND")
                } else {
                    // Car deploys VD server via ADB (USB or TCP).
                    if (isAdbAvailable() && !vdServerStarted) {
                        carLogSend("ADB available at handshake — deploying VD server")
                        deployVdServer()
                    } else if (!vdServerStarted) {
                        carLogSend("Handshake OK — attempting deploy/fallback to phone")
                        deployVdServer()
                    }
                }
            }
            ControlMsg.VD_PORTS_BOUND -> {
                carLogSend("VD ports bound — connecting video and input directly to VD server")
                val host = phoneHost
                if (host != null) {
                    connectVideoAndInput(host)
                } else {
                    carLogSend("ERROR: phoneHost is null when VD_PORTS_BOUND received")
                }
            }
            ControlMsg.APP_STARTED -> {}
            ControlMsg.UPDATING_CAR -> {
                carLogSend("Phone is updating car app — waiting for restart")
                updatingFromPhone = true
                _statusMessage.value = getString(R.string.status_updating_car)
            }
            ControlMsg.VD_STACK_EMPTY -> {
                carLogSend("VD stack empty — switching to home")
                _vdStackEmpty.tryEmit(Unit)
            }
        }
    }

    private var videoFrameCount = 0L
    private var offscreenTexture: android.graphics.SurfaceTexture? = null
    private var offscreenSurface: android.view.Surface? = null

    private fun handleVideoFrame(frame: FrameCodec.Frame) {
        val isConfig = frame.messageType == VideoMsg.CONFIG
        videoFrameCount++
        if (isConfig || videoFrameCount % 60 == 0L) {
            carLogSend("Video ${if (isConfig) "CONFIG" else "FRAME"} size=${frame.payload.size} total=$videoFrameCount")
        }

        // CONFIG is cached inside VideoDecoder; P-frames before the decoder starts
        // are dropped there (onFrameReceived checks running). Do NOT start the
        // decoder on an offscreen SurfaceTexture(0): those decoded frames have no
        // consumer, wasting hardware-decode bandwidth and a temporary gralloc
        // allocation. Wait for MirrorScreen's real surface, then start normally.

        if (!_videoReady.value && !isConfig) {
            _videoReady.value = true
            carLogSend("First video frame — VD stream ready")
            checkAndAdvance()
        }
        videoDecoder.onFrameReceived(isConfig, frame.payload)
    }

    private fun handleDataFrame(frame: FrameCodec.Frame) {
        when (frame.messageType) {
            DataMsg.APP_LIST -> {
                val apps = AppListMessage.decode(frame.payload).apps
                // Set the app list immediately so the grid can render placeholder
                // (category-icon) tiles while icons are still decoding. The phone's
                // icon PNGs are persisted on Dispatchers.IO below — never on Main.
                _appList.value = apps
                scope.launch(Dispatchers.IO) {
                    var newIcons = 0
                    apps.forEach { app ->
                        if (app.iconPng.isNotEmpty()) {
                            ServerApp.iconCache.putSource(app.packageName, app.iconPng)
                            newIcons++
                        }
                    }
                    // Decode + resize all icons on a background thread BEFORE the grid
                    // renders. After prepareAll() finishes, getPrepared() is an O(1)
                    // ConcurrentHashMap lookup — zero work during scroll.
                    val density = applicationContext.resources.displayMetrics.density
                    val gridIconPx = (64 * density).toInt()
                    val prepared = ServerApp.iconCache.prepareAll(apps, gridIconPx)
                    carLogSend("App list: ${apps.size} apps, ${prepared} icons prepared @ ${gridIconPx}px")
                }
            }
            DataMsg.MEDIA_METADATA -> { _mediaMetadata.value = MediaMetadata.decode(frame.payload) }
            DataMsg.MEDIA_PLAYBACK_STATE -> { _playbackState.value = PlaybackState.decode(frame.payload) }
            DataMsg.APP_UNINSTALLED -> {
                val pkg = String(frame.payload, Charsets.UTF_8)
                _appList.value = _appList.value.filter { it.packageName != pkg }
                ServerApp.iconCache.evict(pkg)
                carLogSend("App uninstalled: $pkg — removed from grid, evicted icon cache")
            }
            DataMsg.APP_INFO_DATA -> {
                val info = AppInfoDataMessage.decode(frame.payload)
                _appInfoData.value = info
                carLogSend("App info received: ${info.packageName} v${info.versionName}")
            }
            DataMsg.LOG_TOGGLE -> {
                val enabled = frame.payload.isNotEmpty() && frame.payload[0].toInt() == 1
                logWriter.setEnabled(enabled)
                // Frame-stats diagnostics ride on the same toggle — when the user
                // enables logging from the phone, the per-30-frame decode-time
                // and queue-depth stats are surfaced too (Phase L1 / perf 9.3).
                videoDecoder.debugFrameStats = enabled
                carLogSend("Logging ${if (enabled) "enabled" else "disabled"} by phone")
            }
        }
    }

    // ─── VD Server Deploy ───
    // (deployVdServer / deployVdServerDirect live in VdServerDeployer; the
    //  service exposes them via deployVdServer() and the deployer's deployDirect().)

    // ─── Actions from car UI ───

    var vdWidth = 1408
        private set
    var vdHeight = 792
        private set
    var targetFps: Int = VideoConfig.TARGET_FPS
        private set

    /** Public log method for UI components (MirrorScreen, etc.) to route logs to phone */
    fun log(msg: String) = carLogSend(msg)

    fun releaseOffscreenSurface() {
        offscreenSurface?.release()
        offscreenSurface = null
        offscreenTexture?.release()
        offscreenTexture = null
    }

    fun sendTouchEvent(event: TouchEvent) = touchSender.sendTouchEvent(event)

    fun sendTouchBatch(pointers: List<TouchEvent>) = touchSender.sendTouchBatch(pointers)

    /** Send a control command to the VD server directly via the input connection (port 9639) */
    private fun sendCommandToVd(msgType: Byte, payload: ByteArray = ByteArray(0)) {
        scope.launch(Dispatchers.IO) {
            try {
                inputConnection?.sendControl(msgType, payload)
            } catch (e: Exception) {
                carLogSend("sendCommandToVd 0x${msgType.toString(16)} failed: ${e.message}", "E")
            }
        }
    }

    fun launchApp(packageName: String) {
        sendCommandToVd(ControlMsg.LAUNCH_APP, LaunchAppMessage(packageName).encode())
    }

    fun goRecent() {
        sendCommandToVd(com.dilinkauto.protocol.ControlMsg.GO_RECENT)
    }

    fun goHome() {
        sendCommandToVd(ControlMsg.GO_HOME)
    }

    fun goBack() {
        sendCommandToVd(ControlMsg.GO_BACK)
    }

    /**
     * Called by MainActivity.onConfigurationChanged when the car panel rotates.
     * If the viewport dimensions changed, re-handshake on the existing control
     * connection so the phone recreates the VD at the new orientation. Video
     * and input connections are recycled; the control channel stays alive.
     *
     * Mid-stream re-handshake: the phone tears down the old VD server and
     * deploys a fresh one at the new dims, then re-binds 9638/9639 and sends
     * VD_PORTS_BOUND again — same flow as the initial connect, just without
     * re-establishing the control TCP connection.
     */
    fun onCarViewportChanged(widthPx: Int, heightPx: Int, dpi: Int) {
        val dm = resources.displayMetrics
        val vp = getViewportSize(widthPx, heightPx, dm.density)
        val newVpW = vp.first
        val newVpH = vp.second
        if (newVpW == vdWidth && newVpH == vdHeight) return
        if (_state.value != State.STREAMING && _state.value != State.CONNECTED) return
        val ctrl = controlConnection ?: return
        if (!ctrl.isConnected) return
        carLogSend("Car viewport changed -> re-handshake ${newVpW}x${newVpH} (was ${vdWidth}x${vdHeight})")
        // Cancel discovery retry loops for the duration of the re-handshake. The
        // control connection is reused (not torn down), but startWifiTrack's
        // gateway retry loop keys on `!handshakeDone && state == CONNECTING` —
        // both of which the teardown below sets — and would race to call
        // connectToPhone, whose handshakeDone guard is now false, disconnecting
        // the live ctrl mid-re-handshake. Mirrors the cancellation pattern at
        // the top of startConnection(). Loops restart on the next full
        // startConnection() if this re-handshake fails and falls back to reconnect.
        connectionScope?.cancel()
        connectJob?.cancel()
        connectJob = null

        // Tear down video/input synchronously  clear disconnect listeners so handleDisconnect()
        // is not invoked for this intentional mid-stream rotation teardown.
        videoConnection?.clearDisconnectListener()
        inputConnection?.clearDisconnectListener()
        videoConnection?.disconnect(); videoConnection = null
        inputConnection?.disconnect(); inputConnection = null
        videoDecoder.stop()
        releaseOffscreenSurface()
        _videoReady.value = false
        wifiReady = false
        vdServerStarted = false
        handshakeDone = false
        vdWidth = newVpW
        vdHeight = newVpH
        _state.value = State.CONNECTING
        _statusMessage.value = getString(R.string.status_starting_vd)

        scope.launch(Dispatchers.IO) {
            val handshake = buildHandshakeRequest(
                context = this@CarConnectionService,
                screenWidth = newVpW,
                screenHeight = newVpH,
                screenDpi = dpi,
                targetFps = targetFps,
                dpiOverride = startupDpi
            )
            try {
                ctrl.sendControl(ControlMsg.HANDSHAKE_REQUEST, handshake.encode())
            } catch (e: Exception) {
                carLogSend("Re-handshake send failed: ${e.message}")
                handleDisconnect()
            }
        }
    }

    fun requestUninstall(packageName: String) {
        sendCommandToVd(ControlMsg.APP_UNINSTALL, packageName.toByteArray(Charsets.UTF_8))
        carLogSend("Requested uninstall: $packageName")
    }

    fun clearAppInfoData() {
        _appInfoData.value = null
    }

    fun requestAppInfo(packageName: String) {
        sendCommandToVd(ControlMsg.APP_INFO, packageName.toByteArray(Charsets.UTF_8))
        carLogSend("Requested app info: $packageName")
    }

    fun disconnectFromPhone() {
        carLogSend("User disconnect — will not auto-reconnect")
        userDisconnected = true
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendControl(ControlMsg.DISCONNECT) } catch (_: Exception) {}
            disconnectAllConnections()
        }
    }

    fun sendMediaAction(action: MediaAction) {
        scope.launch(Dispatchers.IO) {
            try { controlConnection?.sendData(DataMsg.MEDIA_ACTION, byteArrayOf(action.id)) }
            catch (e: Exception) { carLogSend("mediaAction failed: ${e.message}", "E") }
        }
    }

    // ─── Disconnect & Reconnect ───

    private fun handleDisconnect() {
        carLogSend("handleDisconnect — state=${_state.value} usb=${usbReady} wifi=${wifiReady}")
        connectionScope?.cancel()
        connectionScope = null
        connectJob?.cancel()
        connectJob = null

        videoDecoder.stop()
        releaseOffscreenSurface()
        // Release icon cache: ~5-9MB of retained bitmaps + source PNGs on eMMC.
        // Phone resends icons on next APP_LIST, so retaining across sessions is pure waste.
        ServerApp.iconCache.clear()
        // Don't disconnect ADB controller on every disconnect — TCP ADB connections
        // survive WiFi flaps. Only null it if the connection is actually broken.
        if (adbController?.isConnected != true) {
            adbController?.disconnect()
            adbController = null
        }
        disconnectAllConnections()

        _phoneName.value = ""
        _appList.value = emptyList()
        _videoReady.value = false
        videoFrameCount = 0
        touchSender.resetCounters()
        wifiReady = false
        vdServerStarted = false
        handshakeDone = false
        if (usbAdb?.isConnected != true) {
            usbReady = false
            if (usbAdb == null) usbConnecting = false
        }
        // Preserve TCP ADB readiness if controller is still connected
        if (adbController?.isConnected == true) {
            usbReady = true
        }

        if (updatingFromPhone) {
            _state.value = State.IDLE
            _statusMessage.value = getString(R.string.status_updating_please_wait)
            carLogSend("Disconnected during update — waiting for app restart")
            // Don't reconnect — the phone will install the new APK and restart us
            return
        }

        if (userDisconnected) {
            _state.value = State.IDLE
            // Don't clear userDisconnected — persisted until USB re-plug or manual START
            usbReady = false
            carLogSend("User disconnect — idle (persisted)")
            scope.launch { updateNotification(R.string.notification_searching) }
            return
        }

        _state.value = State.IDLE

        if (noAdbCount >= 3) {
            _statusMessage.value = getString(R.string.status_no_adb)
            carLogSend("No ADB after $noAdbCount attempts — stopping reconnect. Plug phone into car USB.")
            scope.launch { updateNotification(R.string.plug_phone_usb) }
            return
        }

        carLogSend("Connection lost — will reconnect")

        scope.launch {
            consecutiveFailures++
            val backoffMs = if (consecutiveFailures <= 1) 500L
                else (500L * (1L shl (consecutiveFailures - 1).coerceAtMost(4))).coerceAtMost(8000L)
            carLogSend("Reconnect backoff: ${backoffMs}ms (failures=$consecutiveFailures)")
            delay(backoffMs)
            if (_state.value == State.IDLE && !userDisconnected) {
                startConnection()
            }
        }
    }

    private fun shutdown() {
        videoDecoder.stop()
        adbController?.disconnect()
        adbController = null
        disconnectAllConnections()
        phoneHost = null
        _state.value = State.IDLE
        logWriter.shutdown()
        scope.cancel()
    }

    // ─── WiFi helpers ───

    private fun getWifiGatewayIp(): String? {
        // Dev mode: check for manual phone IP in SharedPreferences first
        if (devMode) {
            val devIp = getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE)
                .getString("dev_phone_ip", null)
            if (!devIp.isNullOrBlank()) return devIp
        }
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            WifiGatewayIp.format(wm.dhcpInfo.gateway)
        } catch (e: Exception) { null }
    }

    /** Deploy VD server using the Dadb connection directly, bypassing isConnected check */
    private suspend fun deployVdServerDirect(controller: RemoteAdbController) =
        vdDeployer.deployDirect(controller)

    // ─── Car Log (sent to phone via protocol, phone writes to file) ───

    internal fun carLogSend(msg: String, level: String = "I") = logWriter.send(msg, level)

    /** Status sink for the deployer and tracks. */
    internal fun setStatusMessage(resId: Int, vararg formatArgs: Any) {
        _statusMessage.value = if (formatArgs.isEmpty()) getString(resId) else getString(resId, *formatArgs)
    }

    // ─── System ───

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiLinkAuto::CarConnectionService")
            .apply { acquire(4 * 60 * 60 * 1000L) } // 4h auto-release
    }

    private fun buildNotification(messageRes: Int): Notification {
        return NotificationCompat.Builder(this, ServerApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(messageRes))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(messageRes: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(messageRes))
    }

    override fun onDestroy() {
        shutdown()
        touchSender.shutdown()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        networkCallback?.let {
            try { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
            catch (_: Exception) {}
        }
        networkCallback = null
        usbAdb?.close()
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CarConnectionService"
        const val ACTION_START = "com.dilinkauto.server.START"
        const val ACTION_CONNECT = "com.dilinkauto.server.CONNECT"
        const val ACTION_STOP = "com.dilinkauto.server.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val NOTIFICATION_ID = 2001
        const val NAV_BAR_TARGET_DP = 76f

        fun getViewportSize(widthPx: Int, heightPx: Int, density: Float): Pair<Int, Int> {
            val isLandscape = widthPx > heightPx
            val navBarPx = navBarWidthPx(density, if (isLandscape) widthPx else heightPx)
            val viewportWidth = if (isLandscape) widthPx - navBarPx else widthPx
            val viewportHeight = if (isLandscape) heightPx else heightPx - navBarPx
            return Pair(viewportWidth and 0x7FFFFFFE.toInt(), viewportHeight and 0x7FFFFFFE.toInt())
        }

        fun navBarWidthPx(density: Float, screenWidthPx: Int): Int {
            val targetPx = (NAV_BAR_TARGET_DP * density).toInt()
            val viewport = screenWidthPx - targetPx
            return if (viewport % 2 != 0) targetPx + 1 else targetPx
        }
    }
}



