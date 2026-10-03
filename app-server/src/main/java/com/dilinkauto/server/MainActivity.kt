package com.dilinkauto.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.nav.PersistentNavBar
import com.dilinkauto.server.ui.screen.CarLaunchScreen
import com.dilinkauto.server.ui.screen.HomeContent
import com.dilinkauto.server.ui.screen.MirrorContent
import com.dilinkauto.server.ui.theme.CarTheme

class MainActivity : ComponentActivity() {

    private var carService: CarConnectionService? = null
    internal var pendingUsbDevice: android.hardware.usb.UsbDevice? = null
    private var serviceBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            carService = (binder as CarConnectionService.LocalBinder).service
            serviceBound = true
            pendingUsbDevice?.let { device ->
                carService?.onUsbDeviceFromActivity(device)
                pendingUsbDevice = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            carService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceIntent = Intent(this, CarConnectionService::class.java).apply {
            action = CarConnectionService.ACTION_START
        }
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Handle USB device attached intent (from manifest intent-filter)
        handleUsbIntent(intent)

        setContent {
            CarTheme {
                if (serviceBound) {
                    carService?.let { service ->
                        CarShell(service)
                    }
                }
            }
        }
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
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}

enum class Screen {
    HOME, APP
}

@Composable
fun CarShell(service: CarConnectionService) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    val state by service.state.collectAsState()
    val appList by service.appList.collectAsState()
    val statusMessage by service.statusMessage.collectAsState()
    val videoReady by service.videoReady.collectAsState()
    val isConnected = state == CarConnectionService.State.STREAMING ||
            state == CarConnectionService.State.CONNECTED

    // During a mid-stream rotation, onCarViewportChanged sets state to CONNECTING but
    // keeps the app list (a real disconnect clears it in handleDisconnect). Keep the
    // streaming layout visible so CarContentArea's video-wait overlay covers the ~2s
    // VD redeploy gap instead of flashing the full CarLaunchScreen.
    val showStreamingMode = appList.isNotEmpty() &&
            (isConnected || state == CarConnectionService.State.CONNECTING)

    // When VD stack empties (after back presses), go to home screen
    LaunchedEffect(Unit) {
        service.vdStackEmpty.collect {
            currentScreen = Screen.HOME
        }
    }

    val launchApp: (String) -> Unit = { pkg ->
        service.launchApp(pkg)
        currentScreen = Screen.APP
    }

    // App info dialog
    val appInfoData by service.appInfoData.collectAsState()
    if (appInfoData != null) {
        val info = appInfoData!!
        val dateStr = remember(info.installTime) {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(info.installTime))
        }
        AlertDialog(
            onDismissRequest = { service.clearAppInfoData() },
            title = { Text(info.appName, color = Color.White, fontSize = 20.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Package", info.packageName)
                    InfoRow("Version", info.versionName)
                    InfoRow("Version code", info.versionCode.toString())
                    InfoRow("Target SDK", info.targetSdk.toString())
                    InfoRow("Installed", dateStr)
                }
            },
            confirmButton = {
                TextButton(onClick = { service.clearAppInfoData() }) {
                    Text("OK", fontSize = 16.sp)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = Color.White,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showStreamingMode) {
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                PersistentNavBar(
                    onBack = { service.goBack() },
                    onHome = {
                        service.goHome()
                        currentScreen = Screen.HOME
                    },
                    onDisconnect = {
                        service.disconnectFromPhone()
                        currentScreen = Screen.HOME
                    }
                )

                CarContentArea(
                    service = service,
                    currentScreen = currentScreen,
                    videoReady = videoReady,
                    statusMessage = statusMessage,
                    launchApp = launchApp,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                CarContentArea(
                    service = service,
                    currentScreen = currentScreen,
                    videoReady = videoReady,
                    statusMessage = statusMessage,
                    launchApp = launchApp,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                com.dilinkauto.server.ui.nav.PersistentBottomNavBar(
                    onBack = { service.goBack() },
                    onHome = {
                        service.goHome()
                        currentScreen = Screen.HOME
                    },
                    onDisconnect = {
                        service.disconnectFromPhone()
                        currentScreen = Screen.HOME
                    }
                )
            }
        }
    } else {
        // Full-screen launch / connection screen — no nav bar, connection-focused
        CarLaunchScreen(service = service)
    }
}

/**
 * Shared content area for streaming mode. Renders the mirror surface, the
 * "waiting for video" overlay when the stream isn't ready, and the home
 * screen. Used by both the landscape (Row) and portrait (Column) layouts in
 * [CarShell] so the two branches differ only in nav-bar placement.
 */
@Composable
private fun CarContentArea(
    service: CarConnectionService,
    currentScreen: Screen,
    videoReady: Boolean,
    statusMessage: String,
    launchApp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        MirrorContent(service = service, visible = currentScreen == Screen.APP)
        val showVideoWaitOverlay = !videoReady && currentScreen == Screen.APP
        when {
            showVideoWaitOverlay -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                        androidx.compose.material3.Text(
                            statusMessage.ifEmpty { stringResource(R.string.status_starting_vd) },
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 18.sp
                        )
                    }
                }
            }
            currentScreen == Screen.HOME -> HomeContent(service = service, onAppClick = launchApp)
        }
    }
}

@androidx.compose.runtime.Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "$label:",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
        Text(
            value,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}
