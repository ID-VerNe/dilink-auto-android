package com.dilinkauto.server.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.dilinkauto.protocol.*
import com.dilinkauto.server.R
import com.dilinkauto.server.ServerApp
import com.dilinkauto.server.CarCrashHandler
import com.dilinkauto.server.CarCrashReport
import com.dilinkauto.server.adb.RemoteAdbController
import com.dilinkauto.server.adb.WifiGatewayProbe
import com.dilinkauto.protocol.adb.UsbAdbConnection
import com.dilinkauto.server.decoder.VideoDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private lateinit var notifier: ForegroundNotifier
    private var consecutiveFailures = 0
    private var usbAdb: UsbAdbConnection? = null
    /**
     * SharedPreferences facade (audit R3-SRP-01 item 9) — owns every pref key this
     * service touches. Lazy construction: a Service's field initializers run before
     * `attachBaseContext`, and opening the prefs file needs the base context.
     */
    internal val carPrefs: CarPrefs by lazy { CarPrefs(this) }

    /**
     * Persist a new startup FPS *and* apply it to the live [targetFps], so the
     * running decoder and the next handshake pick it up immediately. DPI/bitrate
     * have no live counterpart — they are read at handshake construction time.
     */
    fun setStartupFps(value: Int) {
        carPrefs.startupFps = value
        targetFps = value
    }

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
    /**
     * Parent job for all discovery/connect coroutines (WiFi retry, mDNS, USB
     * scan, TCP-ADB retry). Cancelling it must stop every one of those loops —
     * that is what it is for (audit S-04).
     *
     * Deliberately a bare parent [Job] assigned *before* the track coroutines
     * are launched: the loops read it via [trackScope] and must find a live
     * home, not a coroutine whose body has not run yet.
     */
    private var connectionScope: Job? = null
    /**
     * mDNS discovery flow job (audit S-M2). Held so a new [startConnection]
     * cancels the previous listener before launching another one — each launch
     * registers a fresh `NsdManager.DiscoveryListener` and `Discovery`'s
     * `awaitClose` only runs when the collecting job is cancelled.
     */
    private var discoveryJob: Job? = null
    @Volatile internal var vdServerStarted = false // VD server process launched
    @Volatile private var shizukuMode = false  // Phone handles VD server via Shizuku
    @Volatile private var handshakeDone = false // Stop gateway retry after handshake completes
    @Volatile private var lastAdbHost: String? = null // Track which host TCP ADB connected to
    internal var noAdbCount = 0 // Consecutive deploy failures due to no ADB — stops reconnect loop
    @Volatile private var tcpAdbConnecting = false // Prevent duplicate TCP ADB attempts
    /**
     * True while a mid-stream re-handshake owns the control connection
     * (audit S-04). The WiFi retry loop and mDNS callbacks key on
     * `!handshakeDone && state == CONNECTING`, both of which the re-handshake
     * sets — without this guard they race into `connectToPhone`, whose only
     * protection (the handshakeDone guard) is gone, and tear down the very
     * control connection carrying the re-handshake.
     */
    @Volatile private var reHandshakeInFlight = false
    /**
     * Per-session VD rebuild counter for the black-screen self-heal
     * (audit S-M1). The old gate required `vdServerStarted`, which is never
     * set in Shizuku mode and which the recovery itself clears — so the
     * self-heal fired at most once per deployment and then silently died.
     * Reset by [startConnection]; capped at [MAX_BLACK_SCREEN_REBUILDS] so a
     * phone that keeps serving black frames cannot drive a rebuild storm.
     */
    private var blackScreenRebuilds = 0
    /**
     * Teardown generation guard (audit S-05). `handleDisconnect` runs from
     * `Connection.disconnect()` listeners, and `disconnectAllConnections()`
     * defaults to `clearListeners = false` — so an intentional teardown
     * re-enters itself, duplicating `consecutiveFailures++`, re-scheduling
     * `startConnection()`, and overwriting `connectionScope`/`connectJob`.
     * One teardown per generation; re-armed by [startConnection].
     */
    private val teardownGuard = AtomicBoolean(false)

    /**
     * Single-thread executor that owns decoder start/stop (audit S-M3).
     * `SurfaceHolder.Callback` fires on the main thread, and
     * `MediaCodec.createDecoderByType` + `configure` + `stop/release` are
     * expensive enough to be visible as jank on the 8x A53 head unit on every
     * HOME↔APP navigation. MirrorScreen hands the Surface over and these run
     * off the main thread. Serial execution also keeps start/stop from
     * interleaving with each other.
     */
    private val decoderExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            // Daemon like the touch-sender thread: this is purely lifecycle
            // work, nothing here may hold the process (or a test JVM) open.
            Thread(r, "CarDecoderLifecycle").apply { isDaemon = true }
        }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val usbPermissionAction = "com.dilinkauto.server.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        onUsbDeviceAttached(device)
                        // A device that attaches after the initial scan has run must
                        // re-arm the track, or `startUsbTrack`'s one-shot scan is
                        // long gone and USB ADB never connects (audit S-M7).
                        startUsbTrack()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    carLogSend("USB device detached")
                    val wasAdbTrack = usbAdb != null
                    usbAdb?.close()
                    usbAdb = null
                    usbReady = false
                    // The session is now half-dead: UI still shows STREAMING but
                    // the deploy track is gone, so every later rotation
                    // re-handshake fails. End it via the same teardown path a
                    // real disconnect uses so the state machine returns to IDLE
                    // and reconnects cleanly (audit S-M7).
                    if (wasAdbTrack &&
                        (_state.value == State.STREAMING || _state.value == State.CONNECTED)
                    ) {
                        carLogSend("USB ADB detached mid-session — ending session", "W")
                        scope.launch { handleDisconnect() }
                    }
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
        // Foreground plumbing shared with the phone service (DRY-6). The car has
        // no user-facing Stop action, so extraAction stays null.
        notifier = ForegroundNotifier(
            context = this,
            channelId = ServerApp.CHANNEL_SERVICE,
            notificationId = NOTIFICATION_ID,
            wakeLockTag = "DiLinkAuto::CarConnectionService",
            title = getString(R.string.notification_title),
            text = { ctx, res -> ctx.getString(res) }
        )
        // carPrefs initialises its observable mirrors from the persisted values,
        // so the UI renders the stored settings from its first frame (SRP-06).
        targetFps = carPrefs.startupFps
        logWriter.setEnabled(com.dilinkauto.server.BuildConfig.DEBUG)
        logWriter.start()
        videoDecoder.logSink = { msg -> carLogSend(msg) }
        // Self-heal: a stream that stays black for seconds means the phone's
        // VirtualDisplay is producing nothing (stuck DTA, leaked display,
        // compositor wedged). Nothing on the car side can fix that — only a
        // fresh VD on the phone. Re-handshake makes the phone tear down and
        // redeploy the engine, which is exactly the recovery path.
        //
        // Gated on the live session being alive — NOT on `vdServerStarted`,
        // which is only set by a successful ADB deploy (so Shizuku mode never
        // self-heals) and which the recovery itself clears (so it worked at
        // most once per deployment and then silently died forever). The
        // per-session counter [blackScreenRebuilds] still bounds the storm:
        // a phone that keeps serving black frames gets at most
        // [MAX_BLACK_SCREEN_REBUILDS] rebuilds per session (audit S-M1).
        videoDecoder.onSustainedBlackScreen = {
            val sessionAlive = (_state.value == State.STREAMING || _state.value == State.CONNECTED) &&
                handshakeDone
            if (sessionAlive && !reHandshakeInFlight &&
                blackScreenRebuilds < MAX_BLACK_SCREEN_REBUILDS && !blackScreenRecoveryInFlight
            ) {
                blackScreenRebuilds++
                blackScreenRecoveryInFlight = true
                carLogSend("[BLACK] requesting VD rebuild via re-handshake (rebuild $blackScreenRebuilds/$MAX_BLACK_SCREEN_REBUILDS this session)", "W")
                scope.launch(Dispatchers.IO) {
                    try { rehandshakeForBlackScreen() } catch (e: Exception) {
                        carLogSend("[BLACK] rebuild failed: ${e.message}", "E")
                    } finally { blackScreenRecoveryInFlight = false }
                }
            }
        }
        // Wire crash handler to TCP log sink for immediate crash delivery
        CarCrashHandler.logSink = { msg -> carLogSend(msg) }

        // Log device info for diagnostics
        carLogSend(CarCrashReport.deviceInfo(this))

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
                // Foreground first: if the service was started with
                // startForegroundService, the system requires startForeground()
                // within ~5s even when the extras turn out to be unusable.
                startForeground(NOTIFICATION_ID, buildNotification(R.string.notification_searching))
                // Peer-supplied extras must be validated before they reach a
                // socket (audit S-L1): a blank host would fail deep inside
                // NIO and an out-of-range port is meaningless.
                val host = intent.getStringExtra(EXTRA_HOST)?.trim()
                val port = intent.getIntExtra(EXTRA_PORT, Ports.DEFAULT_PORT)
                if (host.isNullOrEmpty()) {
                    carLogSend("ACTION_CONNECT rejected: blank host", "W")
                    _statusMessage.value = getString(R.string.status_blank_host)
                    return START_STICKY
                }
                if (port !in 1..65535) {
                    carLogSend("ACTION_CONNECT rejected: port $port out of range", "W")
                    _statusMessage.value = getString(R.string.status_bad_port)
                    return START_STICKY
                }
                carPrefs.userDisconnected = false
                _state.value = State.CONNECTING
                beginConnect(host, port)
            }
            ACTION_STOP -> {
                shutdown()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun connectManual(host: String, port: Int = Ports.DEFAULT_PORT) {
        carPrefs.userDisconnected = false
        _state.value = State.CONNECTING
        beginConnect(host, port)
    }

    /**
     * Single entry point for a *targeted* connect (manual IP or ACTION_CONNECT).
     *
     * Both used to call [connectToPhone] alone, which never started the
     * USB/TCP-ADB track — so `checkAndAdvance` waited forever on
     * `usbReady || shizukuMode` and the UI spun in CONNECTING until timeout
     * (audit S-M6). The USB track also has to be a child of [connectionScope]
     * so the retry loop can actually be cancelled (audit S-04).
     */
    private fun beginConnect(host: String, port: Int) {
        connectionScope?.cancel()
        val parent = Job()
        connectionScope = parent
        scope.launch(parent) {
            startUsbTrack()
        }
        connectToPhone(host, port)
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
        // Cancel the previous mDNS discovery listener before launching a new
        // one (audit S-M2) — each relaunch registers another
        // NsdManager.DiscoveryListener and the old ones were never stopped.
        discoveryJob?.cancel()
        discoveryJob = null
        disconnectAllConnections()

        // New session generation: re-arm the teardown guard and the
        // black-screen rebuild budget (audit S-05 / S-M1).
        teardownGuard.set(false)
        blackScreenRebuilds = 0

        // Reset prerequisite flags (keep usbReady if USB is physically connected)
        wifiReady = false
        vdServerStarted = false
        shizukuMode = false
        vdDeployer.reset()
        handshakeDone = false
        _videoReady.value = false
        resetAdbReadiness()

        logWriter.setEnabled(com.dilinkauto.server.BuildConfig.DEBUG)  // Reset to default each session
        // Drop the previous session's buffered log lines (audit S-L4).
        logWriter.beginSession()
        carPrefs.userDisconnected = false
        _state.value = State.CONNECTING
        _statusMessage.value = getString(R.string.status_connecting)
        updateNotification(R.string.notification_searching)

        // Launch both tracks under a single parent job — cancelling connectionScope
        // kills all child coroutines (discovery, mDNS, USB scan)
        val parent = Job()
        connectionScope = parent
        scope.launch(parent) {
            startWifiTrack()
            startUsbTrack()
        }
    }

    /**
     * Scope that owns the track loops of the current connection generation.
     *
     * [connectionScope] is now a plain parent [Job] assigned *before* the track
     * coroutines launch, so the loops are guaranteed to have a live home
     * (audit S-04). A `null` scope means no connection generation has started
     * yet — e.g. a USB hot-plug broadcast arrived before ACTION_START — and
     * the service scope is the right (only) home for that one-shot work. A
     * *cancelled* scope is deliberately NOT substituted: launches on it are
     * no-ops, which is exactly what the re-handshake window wants (the
     * `reHandshakeInFlight` guard in `connectToPhone` covers callbacks that
     * were already dispatched before the cancel landed).
     */
    private fun trackScope(): CoroutineScope =
        connectionScope?.let { CoroutineScope(it + Dispatchers.Main.immediate) } ?: scope

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
        // and Shizuku mode where phone's service may not be listening on first attempt.
        //
        // Launched on trackScope(), NOT the service scope: `connectionScope?.cancel()`
        // must be able to stop these loops, or they keep firing during the
        // rotation re-handshake window (audit S-04).
        trackScope().launch(Dispatchers.IO) {
            delay(500)
            while (isActive && !wifiReady && !handshakeDone && _state.value == State.CONNECTING) {
                val gatewayIp = getWifiGatewayIp()
                if (gatewayIp != null) {
                    carLogSend("WiFi track: trying gateway $gatewayIp")
                    connectToPhone(gatewayIp, Ports.DEFAULT_PORT)
                }
                delay(3000)
            }
        }

        // Strategy 2: mDNS (runs continuously until wifiReady or handshakeDone).
        // Same connectionScope ownership as strategy 1; the job reference is
        // kept so the next startConnection() cancels this listener before
        // launching a replacement (audit S-M2).
        discoveryJob?.cancel()
        discoveryJob = trackScope().launch {
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
        // Defence in depth for the rotation re-handshake window (audit S-04):
        // a re-handshake clears handshakeDone and sets state=CONNECTING, which
        // is exactly when the retry loops and mDNS callbacks wake up. Both
        // loops are now connectionScope children, but a callback already
        // dispatched must still not tear down the control connection that is
        // carrying the re-handshake.
        if (reHandshakeInFlight) {
            carLogSend("connectToPhone: re-handshake in flight, skipping ($host:$port)")
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
                val vp = CarViewport.size(displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.density)
                val viewportWidth = vp.first
                val viewportHeight = vp.second
                sendHandshake(ctrl, viewportWidth, viewportHeight, displayMetrics.densityDpi)
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
     * Build + send a HANDSHAKE_REQUEST on [ctrl].
     *
     * Shared by the initial connect path and the mid-stream re-handshake: both
     * pass the same viewport/DPI and read the same persisted settings, so the
     * request fields cannot drift between the two call sites (they had
     * converged by hand before; this makes it structural).
     */
    private fun sendHandshake(ctrl: Connection, widthPx: Int, heightPx: Int, dpi: Int) {
        val handshake = buildHandshakeRequest(
            context = this,
            screenWidth = widthPx,
            screenHeight = heightPx,
            screenDpi = dpi,
            targetFps = targetFps,
            dpiOverride = carPrefs.startupDpi,
            bitrate = carPrefs.startupBitrate
        )
        ctrl.sendControl(ControlMsg.HANDSHAKE_REQUEST, handshake.encode())
    }

    /**
     * Opens video and input connections after handshake succeeds.
     * Called from handleControlFrame when HANDSHAKE_RESPONSE is received.
     */
    private fun connectVideoAndInput(host: String) {
        scope.launch(Dispatchers.IO) {
            try {
                carLogSend("Connecting video (${Ports.VIDEO_PORT}) and input (${Ports.INPUT_PORT})...")

                val videoDef = async { Connection.connect(host, Ports.VIDEO_PORT, scope) }
                val inputDef = async { Connection.connect(host, Ports.INPUT_PORT, scope) }

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

    /**
 * Clears the USB-ADB readiness flags when no USB device is actually connected.
 *
 * Shared by [startConnection] and [handleDisconnect], which previously each had
 * their own copy of this block (DRY-12). The two had already drifted: only
 * `startConnection` carried the "auth may be pending" comment explaining why
 * `usbConnecting` is only cleared when `usbAdb` is null.
 *
 * Deliberately does NOT clear readiness when USB is connected — a physical
 * device that survived a reconnect keeps its state. Likewise it does not touch
 * the TCP-ADB controller: `handleDisconnect` restores `usbReady` from
 * `adbController.isConnected` separately, and `startConnection` must not, or it
 * would undo the restart it just triggered.
 */
    private fun resetAdbReadiness() {
        if (usbAdb?.isConnected != true) {
            usbReady = false
            // Only reset if there is no ADB instance at all; a non-null usbAdb
            // may still be waiting on the user to accept the auth prompt.
            if (usbAdb == null) usbConnecting = false
        }
    }

    /**
     * Drops the video/input/control connections and nulls the handles.
     *
     * @param clearListeners also detach the disconnect callbacks. Needed when
     *   tearing down mid-stream on purpose, so [handleDisconnect] does not run
     *   for a teardown we initiated. Omitted when we *want* the disconnect
     *   handler to fire.
     */
    private fun disconnectAllConnections(clearListeners: Boolean = false) {
        // Detach callbacks first so a disconnect we asked for does not re-enter
        // handleDisconnect() and tear down state we are about to rebuild.
        if (clearListeners) {
            videoConnection?.clearDisconnectListener()
            inputConnection?.clearDisconnectListener()
            controlConnection?.clearDisconnectListener()
        }
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
        if (carPrefs.devMode) {
            carLogSend("Development mode active — using TCP ADB instead of USB")
            startTcpAdbTrack()
            return
        }
        // S-04: the scan belongs to the current connection generation.
        trackScope().launch(Dispatchers.IO) {
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
        if (adbController?.isConnected == true && lastAdbHost == phoneHost) {
            // 控制器跨会话存活（dev 模式下 TCP ADB 连的是手机 adbd，不受 App 重启影响）
            // 时直接复用，但 usbReady 可能已被上一轮重连（startConnection 重置）或
            // 用户断开清掉；这里必须就地恢复，否则本函数因「已连接」提前返回，
            // usbReady 再也无法置真，状态机卡死在 CONNECTING（UI 永远显示"连接中"）。
            if (!usbReady) {
                usbReady = true
                carLogSend("Dev mode: TCP ADB still connected — usbReady restored")
                checkAndAdvance()
            }
            return
        }
        if (adbController?.isConnected == true && lastAdbHost != phoneHost && phoneHost != null) {
            carLogSend("Dev mode: phone IP changed $lastAdbHost -> $phoneHost, reconnecting TCP ADB")
            adbController?.disconnect()
            adbController = null
            lastAdbHost = null
        }
        tcpAdbConnecting = true
        trackScope().launch(Dispatchers.IO) {
            try {
                var attempts = 0
                while (isActive && !usbReady && _state.value == State.CONNECTING && attempts < 60) {
                    val host = phoneHost
                    if (host != null) {
                        carLogSend("Dev mode: TCP ADB connecting to $host:${Ports.ADB_PORT} (attempt ${attempts + 1})")
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
            adbPort = Ports.ADB_PORT,
            virtualDisplayId = -1,
            keyDir = keyDir
        )

        if (!controller.connect()) {
            _statusMessage.value = getString(R.string.status_tcp_adb_failed)
            carLogSend("Dev mode: TCP ADB connection failed to $host:${Ports.ADB_PORT}")
            return
        }

        adbController = controller
        _statusMessage.value = getString(R.string.status_tcp_adb_connected)
        carLogSend("Dev mode: TCP ADB connected to $host:${Ports.ADB_PORT}")

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
        carPrefs.userDisconnected = false

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

            // Write key diagnostic to phone for debugging USB auth issues.
            // keyInfo is app-internal (path + fingerprint), but it still goes
            // through an `sh` command line — quote it so a future change that
            // makes it peer-influenced cannot inject shell syntax (audit S-M11).
            val keyInfo = adb.keyDiagnostic()
            adb.shell("echo ${VdDeploy.shellQuote(keyInfo)} > /data/local/tmp/car-adb-key.log")
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
                // Peer-supplied dims go straight into MediaFormat and into the
                // rotation guard (`newVpW == vdWidth`). A zero/negative/absurd
                // value crashes the decoder or permanently suppresses the
                // guard, so clamp on receipt and fall back to the car's own
                // viewport (audit S-M4).
                vdWidth = sanitizePeerDimension(response.displayWidth, vdWidth)
                vdHeight = sanitizePeerDimension(response.displayHeight, vdHeight)
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
            ControlMsg.VD_STACK_EMPTY -> {
                carLogSend("VD stack empty — switching to home")
                _vdStackEmpty.tryEmit(Unit)
            }
        }
    }

    private var videoFrameCount = 0L
    /** One-shot latch: at most one VD rebuild in flight for a sustained black screen. */
    @Volatile private var blackScreenRecoveryInFlight = false
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
                    var totalIconBytes = 0L
                    apps.forEach { app ->
                        if (app.iconPng.isEmpty()) return@forEach
                        // Aggregate cap (audit S-M5): the frame limit is 128MB and
                        // a 4GB car head unit OOMs decoding the whole batch — this
                        // also bounds the eMMC source cache written below.
                        if (!iconBudgetAccepts(totalIconBytes, app.iconPng.size)) {
                            carLogSend("App list: icon budget exhausted after $newIcons icons — skipping the rest", "W")
                            return@launch
                        }
                        totalIconBytes += app.iconPng.size
                        // Per-icon cap: a PNG decompression bomb (small bytes,
                        // huge pixel dimensions) must never reach BitmapFactory.
                        // `inJustDecodeBounds` reads only the header, so this is
                        // cheap (audit S-M5).
                        if (!iconWithinLimits(app.packageName, app.iconPng)) return@forEach
                        ServerApp.iconCache.putSource(app.packageName, app.iconPng)
                        newIcons++
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
        internal set

    /** Public log method for UI components (MirrorScreen, etc.) to route logs to phone */
    fun log(msg: String) = carLogSend(msg)

    /**
     * Clamp a peer-supplied viewport dimension into [MIN_PEER_DIMENSION]..
     * [MAX_PEER_DIMENSION], falling back to [fallback] (the car's own viewport)
     * when it is out of range (audit S-M4).
     *
     * `MediaFormat.createVideoFormat` throws on non-positive dimensions, and
     * the rotation guard compares against these values forever — an unvalidated
     * `0` from a hostile or buggy peer either crashes the decoder on a moving
     * vehicle or silently disables rotation recovery.
     */
    internal fun sanitizePeerDimension(value: Int, fallback: Int): Int =
        value.takeIf { it in MIN_PEER_DIMENSION..MAX_PEER_DIMENSION } ?: fallback

    /**
     * Per-icon sanity gate for APP_LIST payloads (audit S-M5).
     *
     * Returns false (and logs) when the PNG is larger than [MAX_ICON_PNG_BYTES]
     * or declares more than [MAX_ICON_DIMENSION] x [MAX_ICON_DIMENSION] pixels.
     * The header-only `inJustDecodeBounds` decode is what stops a small
     * byte-count PNG that expands to a huge bitmap from OOMing the device.
     */
    private fun iconWithinLimits(packageName: String, png: ByteArray): Boolean {
        if (png.size > MAX_ICON_PNG_BYTES) {
            carLogSend("App icon too large, skipped: $packageName (${png.size}B > $MAX_ICON_PNG_BYTES)", "W")
            return false
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) {
                // Header unreadable — let the normal decode path fail later on
                // its own; refusing here would only lose icons on odd decoders.
                true
            } else if (w > MAX_ICON_DIMENSION || h > MAX_ICON_DIMENSION) {
                carLogSend("App icon too big, skipped: $packageName (${w}x${h} > $MAX_ICON_DIMENSION)", "W")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            carLogSend("App icon header decode failed, keeping: $packageName (${e.message})", "W")
            true
        }
    }

    fun releaseOffscreenSurface() {
        offscreenSurface?.release()
        offscreenSurface = null
        offscreenTexture?.release()
        offscreenTexture = null
    }

    /**
     * Attach a freshly created SurfaceView surface to the decoder.
     *
     * Called from `surfaceCreated` (Main thread); the actual MediaCodec
     * create/configure is handed to [decoderExecutor] so the main thread never
     * pays for it (audit S-M3).
     */
    fun onMirrorSurfaceCreated(surface: Surface) {
        decoderExecutor.execute {
            log("[MirrorScreen] SurfaceView surface created, decoder.isRunning=${videoDecoder.isRunning}")
            if (videoDecoder.isRunning) {
                // Decoder already running (survived a navigation hide/show) —
                // switch surface without restarting: zero frame loss.
                videoDecoder.switchSurface(surface)
                releaseOffscreenSurface()
                log("[MirrorScreen] Decoder surface switched to SurfaceView (no restart)")
            } else {
                // First start — decoder hasn't been created yet
                videoDecoder.start(surface, vdWidth, vdHeight, targetFps)
                releaseOffscreenSurface()
                log("[MirrorScreen] Decoder started on SurfaceView surface")
            }
        }
    }

    /**
     * Detach the surface when the SurfaceView goes away (audit S-M3).
     *
     * Only invalidates the render flag — the decoder keeps running across
     * HOME↔APP navigation so the keyframe cache survives. The decoder is NOT
     * stopped here; handleDisconnect/shutdown own the final stop.
     */
    fun onMirrorSurfaceDestroyed() {
        videoDecoder.invalidateSurface()
        log("[MirrorScreen] SurfaceView surface destroyed — decoder stays running, render gated off")
    }

    fun sendTouchEvent(event: TouchEvent) = touchSender.sendTouchEvent(event)

    fun sendTouchBatch(pointers: List<TouchEvent>) = touchSender.sendTouchBatch(pointers)

    /** Send a control command to the VD server directly via the input connection (port 9639) */
    private fun sendCommandToVd(msgType: Byte, payload: ByteArray = ByteArray(0)) {
        scope.launch(Dispatchers.IO) {
            val tag = "sendCommandToVd 0x${Integer.toHexString(msgType.toInt() and 0xFF)}"
            try {
                val conn = inputConnection
                if (conn == null) {
                    carLogSend("$tag dropped: inputConnection is null (VD server not connected?)", "W")
                    return@launch
                }
                conn.sendControl(msgType, payload)
                carLogSend("$tag sent (${payload.size}B)")
            } catch (e: Exception) {
                carLogSend("$tag failed: ${e.message}", "E")
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
        val vp = CarViewport.size(widthPx, heightPx, dm.density)
        val newVpW = vp.first
        val newVpH = vp.second
        if (newVpW == vdWidth && newVpH == vdHeight) return
        if (_state.value != State.STREAMING && _state.value != State.CONNECTED) return
        val ctrl = controlConnection ?: return
        if (!ctrl.isConnected) return
        carLogSend("Car viewport changed -> re-handshake ${newVpW}x${newVpH} (was ${vdWidth}x${vdHeight})")
        // rehandshakeOnExistingControl is suspend; this entry point is not.
        scope.launch(Dispatchers.IO) { rehandshakeOnExistingControl(ctrl, newVpW, newVpH, dpi) }
    }

    /**
     * Rebuild the phone-side VirtualDisplay after a sustained black stream.
     *
     * Same teardown as a rotation re-handshake, but at the *current* dimensions
     * — there is no viewport change here, only a wedged engine. The phone's
     * `handleHandshake` treats any HANDSHAKE_REQUEST as "drop the old VD and
     * build a new one", which is exactly the recovery a stuck compositor needs.
     */
    private suspend fun rehandshakeForBlackScreen() {
        val ctrl = controlConnection
        if (ctrl == null || !ctrl.isConnected) {
            carLogSend("[BLACK] control link gone, cannot re-handshake", "W")
            return
        }
        if (_state.value != State.STREAMING && _state.value != State.CONNECTED) return
        carLogSend("[BLACK] re-handshake at current viewport ${vdWidth}x$vdHeight")
        rehandshakeOnExistingControl(ctrl, vdWidth, vdHeight, carPrefs.startupDpi)
    }

    /**
     * Tear down video/input + the current engine, then send a fresh
     * HANDSHAKE_REQUEST on the *existing* control connection so the phone
     * redeploys the vd-server and rebinds 9638/9639.
     *
     * Shared by the rotation and black-screen paths. The control channel stays
     * alive throughout; only the streams are recycled.
     */
    private suspend fun rehandshakeOnExistingControl(
        ctrl: Connection,
        newVpW: Int,
        newVpH: Int,
        dpi: Int
    ) {
        // Mark the window (audit S-04): connectToPhone must refuse to run while
        // the control connection is being reused for this re-handshake.
        reHandshakeInFlight = true
        try {
            rehandshakeOnExistingControlLocked(ctrl, newVpW, newVpH, dpi)
        } finally {
            reHandshakeInFlight = false
        }
    }

    private suspend fun rehandshakeOnExistingControlLocked(
        ctrl: Connection,
        newVpW: Int,
        newVpH: Int,
        dpi: Int
    ) {
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
        // mDNS needs its own cancellation — its job now lives on
        // connectionScope, but a reference is kept so the next startConnection
        // cannot leak a second DiscoveryListener (audit S-M2).
        discoveryJob?.cancel()
        discoveryJob = null

        // Tear down video/input synchronously — clear disconnect listeners so
        // handleDisconnect() is not invoked for this intentional mid-stream teardown.
        // The control connection is reused by this re-handshake, so it is left
        // untouched: only the video/input legs go down.
        videoConnection?.clearDisconnectListener()
        inputConnection?.clearDisconnectListener()
        videoConnection?.disconnect(); videoConnection = null
        inputConnection?.disconnect(); inputConnection = null

        // Do NOT stop the decoder here (audit S-03). CarShell keeps the streaming
        // layout while state == CONNECTING, so the SurfaceView is never destroyed
        // and surfaceCreated does not fire again — the only thing that could
        // restart a stopped decoder. Stopping it therefore blacks the screen for
        // the rest of the session: exactly the symptom this recovery exists to
        // prevent. Keeping it running across the redeploy gap also keeps the
        // cached CONFIG/keyframe warm for the new stream.
        //
        // Re-arm the detector by hand: stop() used to do this implicitly, but the
        // decoder now survives the re-handshake, and BlackScreenDetector latches
        // `recoveryFired` until reset — without this, the second rebuild of the
        // per-session budget could never trigger (audit S-M1).
        videoDecoder.resetBlackScreenState()
        releaseOffscreenSurface()

        _videoReady.value = false
        wifiReady = false
        vdServerStarted = false
        handshakeDone = false
        vdWidth = newVpW
        vdHeight = newVpH
        _state.value = State.CONNECTING
        _statusMessage.value = getString(R.string.status_starting_vd)

        try {
            sendHandshake(ctrl, newVpW, newVpH, dpi)
        } catch (e: Exception) {
            carLogSend("Re-handshake send failed: ${e.message}")
            handleDisconnect()
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
        carPrefs.userDisconnected = true
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
        // One teardown per generation (audit S-05). Entering from a non-EOF
        // path (e.g. connectToPhone's catch) lands in disconnectAllConnections(),
        // whose default clearListeners=false lets Connection.disconnect() fire the
        // listener and re-enter here: duplicated teardowns, duplicated
        // consecutiveFailures++, duplicated startConnection() scheduling, each
        // cancelling/overwriting the previous one's connectionScope/connectJob.
        if (!teardownGuard.compareAndSet(false, true)) {
            carLogSend("handleDisconnect: already tearing down this generation — ignoring re-entry", "W")
            return
        }
        carLogSend("handleDisconnect — state=${_state.value} usb=${usbReady} wifi=${wifiReady}")
        connectionScope?.cancel()
        connectionScope = null
        connectJob?.cancel()
        connectJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        reHandshakeInFlight = false

        // Decoder teardown is ordered (it joins the feed thread), so run it on
        // the decoder executor rather than the caller's thread — this can be
        // Main (user disconnect) or a network reader thread (audit S-M3).
        stopDecoderAsync()

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
        // clearListeners = true: this teardown is intentional, so the
        // disconnect callbacks must not fire handleDisconnect again (audit S-05).
        disconnectAllConnections(clearListeners = true)

        _phoneName.value = ""
        _appList.value = emptyList()
        _videoReady.value = false
        videoFrameCount = 0
        touchSender.resetCounters()
        wifiReady = false
        vdServerStarted = false
        handshakeDone = false
        resetAdbReadiness()
        // Preserve TCP ADB readiness across a WiFi flap: the controller may still
        // be connected even though no USB device is present.
        if (adbController?.isConnected == true) usbReady = true

        if (carPrefs.userDisconnected) {
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
            val backoffMs = reconnectBackoffMs(consecutiveFailures)
            carLogSend("Reconnect backoff: ${backoffMs}ms (failures=$consecutiveFailures)")
            delay(backoffMs)
            if (_state.value == State.IDLE && !carPrefs.userDisconnected) {
                startConnection()
            }
        }
    }

    private fun shutdown() {
        // Ordered teardown on the decoder thread, mirroring handleDisconnect.
        stopDecoderAsync()
        adbController?.disconnect()
        adbController = null
        disconnectAllConnections(clearListeners = true)
        phoneHost = null
        _state.value = State.IDLE
        logWriter.shutdown()
        decoderExecutor.shutdown()
        scope.cancel()
    }

    /**
     * Runs VideoDecoder.stop() on the single decoder thread (audit S-M3).
     *
     * Non-blocking by design: `handleDisconnect` can run on Main or on a
     * network reader thread, and the decoder join must not stall either.
     * Serial execution on one thread is what guarantees a queued stop finishes
     * before a subsequent start() (e.g. the reconnect that follows).
     */
    private fun stopDecoderAsync() {
        if (!videoDecoder.isRunning) return  // nothing to do; stop() is idempotent
        try {
            decoderExecutor.execute { videoDecoder.stop() }
        } catch (_: Exception) {
            // Executor already shut down (destroy path) — stop inline so the
            // MediaCodec is still released rather than leaked.
            videoDecoder.stop()
        }
    }

    // ─── WiFi helpers ───

    private fun getWifiGatewayIp(): String? {
        // Dev mode: check for manual phone IP in SharedPreferences first
        if (carPrefs.devMode) {
            val devIp = carPrefs.devPhoneIp
            if (!devIp.isNullOrBlank()) return devIp
        }
        return WifiGatewayProbe.gatewayIp(this)
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

    private fun acquireWakeLock() = notifier.acquireWakeLock()

    private fun buildNotification(messageRes: Int): Notification =
        notifier.buildNotification(messageRes)

    private fun updateNotification(messageRes: Int) = notifier.updateNotification(messageRes)

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
        notifier.releaseWakeLock()
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

        // Viewport geometry lives in [CarViewport] (audit R3-SRP-01 item 11).

        /**
         * Upper bound for a peer-reported viewport dimension (audit S-M4).
         *
         * The realistic maximum on this hardware is a 1080p landscape VD; 4096
         * is generous headroom while still refusing the zero/negative/absurd
         * values a hostile or buggy peer can put in the handshake response —
         * `MediaFormat.createVideoFormat` throws on non-positive dims and the
         * rotation guard compares against these values forever.
         */
        internal const val MAX_PEER_DIMENSION = 4096

        /** Lower bound for a peer-reported viewport dimension (audit S-M4). */
        internal const val MIN_PEER_DIMENSION = 2

        /**
         * Max decoded pixels for one APP_LIST icon (audit S-M5).
         *
         * Launcher icons are 192x192 on the phone; 512 leaves room for future
         * adaptive-icon work while keeping a decompression bomb out.
         */
        internal const val MAX_ICON_DIMENSION = 512

        /** Max PNG byte size for one APP_LIST icon (audit S-M5). */
        internal const val MAX_ICON_PNG_BYTES = 1 shl 20  // 1 MB

        /**
         * Aggregate icon budget for a single APP_LIST message (audit S-M5).
         *
         * The frame payload cap is 128MB and the device has 4GB total RAM —
         * without this, one APP_LIST could pin hundreds of MB in source PNGs
         * and decoded bitmaps. ~60 icons at 1MB each.
         */
        internal const val MAX_APP_LIST_ICON_BYTES = 64L shl 20  // 64 MB

        /**
         * 重连退避（毫秒）：首次 500，之后翻倍，封顶 8000；位移已 coerce 防止大失败计数溢出。
         * 与原先内联在 reconnect 处的公式完全一致，提取为纯函数以便单测。
         */
        internal fun reconnectBackoffMs(consecutiveFailures: Int): Long =
            if (consecutiveFailures <= 1) 500L
            else (500L * (1L shl (consecutiveFailures - 1).coerceAtMost(4))).coerceAtMost(8000L)

        /** 追加 [nextIconBytes] 后是否仍在 [MAX_APP_LIST_ICON_BYTES] 聚合预算内（含边界）。 */
        internal fun iconBudgetAccepts(totalBytes: Long, nextIconBytes: Int): Boolean =
            totalBytes + nextIconBytes <= MAX_APP_LIST_ICON_BYTES

        /**
         * Max VD rebuilds per session for the black-screen self-heal
         * (audit S-M1). Bounded so a phone that keeps serving black frames
         * cannot drive an endless re-handshake loop; reset by startConnection().
         */
        internal const val MAX_BLACK_SCREEN_REBUILDS = 2
    }
}



