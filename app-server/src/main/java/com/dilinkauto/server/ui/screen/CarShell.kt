package com.dilinkauto.server.ui.screen

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.nav.PersistentBottomNavBar
import com.dilinkauto.server.ui.nav.PersistentNavBar

/**
 * Top-level car shell: streaming layout (mirror + nav bar) or launch screen.
 *
 * Extracted from [com.dilinkauto.server.MainActivity] so the Activity holds
 * only its lifecycle concerns (service binding, USB intent forwarding,
 * immersive mode, rotation re-handshake) and this file owns the Compose
 * tree and screen-state routing.
 */
enum class Screen { HOME, APP }

/** UI 层日志 TAG：点击应用等交互记入 logcat（dev 模式下由 CarLogWriter 转发到手机） */
private const val TAG = "CarShell"

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
        Log.i(TAG, "launchApp clicked: $pkg -> switch to Screen.APP")
        service.launchApp(pkg)
        currentScreen = Screen.APP
    }

    // App info dialog (layout in AppInfoDialog, audit R3-SRP-19)
    val appInfoData by service.appInfoData.collectAsState()
    appInfoData?.let { info ->
        AppInfoDialog(info = info, onDismiss = { service.clearAppInfoData() })
    }

    if (showStreamingMode) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

        if (isLandscape) {
            Row(modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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
                    },
                    modifier = Modifier.zIndex(1f)
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
                PersistentBottomNavBar(
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
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            statusMessage.ifEmpty { stringResource(R.string.status_starting_vd) },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
            currentScreen == Screen.HOME -> HomeContent(service = service, onAppClick = launchApp)
        }
    }
}
