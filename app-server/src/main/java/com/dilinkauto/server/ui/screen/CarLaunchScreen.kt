package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dilinkauto.protocol.SettingsFormat
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.theme.SuccessColor
import com.dilinkauto.server.ui.theme.WarningColor

/**
 * Full-screen launch / connection screen — no nav bar, connection-focused.
 *
 * Shown when the car app launches before the phone connection is established
 * and app icons are received. Once connected and app icons arrive, the UI
 * transitions to the streaming mode with nav bar, home, and apps.
 */
@Composable
fun CarLaunchScreen(service: CarConnectionService) {
    val state by service.state.collectAsState()
    val phoneName by service.phoneName.collectAsState()
    val statusMessage by service.statusMessage.collectAsState()
    // Observed, not shadowed — CarPrefs owns the persisted values (SRP-06).
    val devMode by service.carPrefs.devModeFlow.collectAsState()
    val startupDpi by service.carPrefs.startupDpiFlow.collectAsState()
    val startupFps by service.carPrefs.startupFpsFlow.collectAsState()
    val startupBitrate by service.carPrefs.startupBitrateFlow.collectAsState()

    // Both layouts below render the very same card; hoisting the wiring here
    // keeps the 11-argument call and its callbacks in one place.
    val statusCard: @Composable () -> Unit = {
        ConnectionStatusCard(
            state = state,
            phoneName = phoneName,
            statusMessage = statusMessage,
            devMode = devMode,
            onDevModeChange = { service.carPrefs.devMode = it },
            startupDpi = startupDpi,
            onStartupDpiChange = { service.carPrefs.startupDpi = it },
            startupFps = startupFps,
            onStartupFpsChange = { service.setStartupFps(it) },
            startupBitrate = startupBitrate,
            onStartupBitrateChange = { service.carPrefs.startupBitrate = it }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            contentAlignment = Alignment.Center
        ) {
            val isWide = maxWidth > 700.dp

            if (isWide) {
                // Two-column layout for wide screens (car display)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(48.dp)
                ) {
                    // Left: branding + instructions
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        BrandingSection()

                        if (state != CarConnectionService.State.STREAMING &&
                            state != CarConnectionService.State.CONNECTED) {
                            Spacer(Modifier.height(48.dp))
                            HowToConnect()
                        }
                    }

                    // Right: status + action
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        statusCard()

                        if (devMode && (state == CarConnectionService.State.IDLE ||
                                    state == CarConnectionService.State.CONNECTING)) {
                            Spacer(Modifier.height(16.dp))
                            WifiAdbSetupCard()
                        }

                        if (state != CarConnectionService.State.STREAMING &&
                            state != CarConnectionService.State.CONNECTED) {
                            Spacer(Modifier.height(24.dp))
                            ManualConnectBox(onConnect = { ip -> service.connectManual(ip) })
                        }
                    }
                }
            } else {
                // Single-column layout for narrow screens
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 560.dp)
                ) {
                    BrandingSection()
                    Spacer(Modifier.height(32.dp))
                    statusCard()

                    if (devMode && (state == CarConnectionService.State.IDLE ||
                                state == CarConnectionService.State.CONNECTING)) {
                        Spacer(Modifier.height(12.dp))
                        WifiAdbSetupCard()
                    }

                    if (state != CarConnectionService.State.STREAMING &&
                        state != CarConnectionService.State.CONNECTED) {
                        Spacer(Modifier.height(20.dp))
                        HowToConnect()

                        Spacer(Modifier.height(20.dp))
                        ManualConnectBox(onConnect = { ip -> service.connectManual(ip) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandingSection() {
    Icon(
        Icons.Default.PhoneAndroid,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp)
    )
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.branding_title),
        style = MaterialTheme.typography.headlineLarge,
        color = Color.White,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.branding_tagline),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Connection status card with integrated WiFi/USB mode selector.
 * Shows connection indicator, status text, phone name, and the
 * connection mode toggle when not yet streaming.
 */
@Composable
private fun ConnectionStatusCard(
    state: CarConnectionService.State,
    phoneName: String,
    statusMessage: String,
    devMode: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    startupDpi: Int,
    onStartupDpiChange: (Int) -> Unit,
    startupFps: Int,
    onStartupFpsChange: (Int) -> Unit,
    startupBitrate: Int,
    onStartupBitrateChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection indicator dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            when (state) {
                                CarConnectionService.State.STREAMING -> SuccessColor
                                CarConnectionService.State.CONNECTED -> WarningColor
                                CarConnectionService.State.CONNECTING -> WarningColor
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            RoundedCornerShape(6.dp)
                        )
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            CarConnectionService.State.IDLE -> stringResource(R.string.status_ready_to_connect)
                            CarConnectionService.State.CONNECTING -> stringResource(R.string.status_connecting)
                            CarConnectionService.State.CONNECTED -> stringResource(R.string.status_connected)
                            CarConnectionService.State.STREAMING -> stringResource(R.string.status_streaming)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    if (statusMessage.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (phoneName.isNotEmpty()) phoneName else statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state == CarConnectionService.State.CONNECTING ||
                    state == CarConnectionService.State.IDLE
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            // Connection mode selector — always visible when not streaming
            if (state != CarConnectionService.State.STREAMING &&
                state != CarConnectionService.State.CONNECTED) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.1f))
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.dev_mode_title),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            if (devMode) stringResource(R.string.dev_mode_desc_on)
                            else stringResource(R.string.dev_mode_desc_off),
                            color = if (devMode) WarningColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = devMode,
                        onCheckedChange = onDevModeChange,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = WarningColor
                        )
                    )
                }

                Spacer(Modifier.height(12.dp))
                // DPI Settings & Presets
                SettingSection(
                    title = stringResource(R.string.startup_dpi_title),
                    subtitle = stringResource(R.string.startup_dpi_desc),
                    hint = stringResource(R.string.startup_dpi_hint),
                    currentValueText = if (startupDpi > 0) startupDpi.toString() else stringResource(R.string.preset_auto),
                    manualSeed = SettingsFormat.manualSeed(startupDpi, SettingsFormat.DPI_PRESETS),
                    presets = listOf(
                        stringResource(R.string.preset_auto) to SettingsFormat.DPI_AUTO,
                        "140" to 140,
                        stringResource(R.string.preset_dpi_160) to 160,
                        "180" to 180,
                        "200" to 200
                    ),
                    selectedPresetValue = startupDpi,
                    onValueChange = onStartupDpiChange,
                    coerceManualValue = { text ->
                        SettingsFormat.coerceDpi(text.toIntOrNull() ?: SettingsFormat.DPI_AUTO)
                    }
                )

                Spacer(Modifier.height(16.dp))
                // Bitrate Settings & Presets
                SettingSection(
                    title = stringResource(R.string.video_bitrate_title),
                    subtitle = stringResource(R.string.video_bitrate_desc),
                    hint = stringResource(R.string.video_bitrate_hint),
                    currentValueText = SettingsFormat.formatBitrateMbps(startupBitrate),
                    manualSeed = SettingsFormat.manualSeed(
                        startupBitrate, SettingsFormat.BITRATE_PRESETS, divisor = 1_000_000
                    ),
                    presets = listOf(
                        stringResource(R.string.preset_bitrate_2m) to 2_000_000,
                        stringResource(R.string.preset_bitrate_2_5m) to 2_500_000,
                        stringResource(R.string.preset_bitrate_3m) to 3_000_000,
                        stringResource(R.string.preset_bitrate_4m) to 4_000_000,
                        stringResource(R.string.preset_bitrate_6m) to 6_000_000
                    ),
                    selectedPresetValue = startupBitrate,
                    onValueChange = onStartupBitrateChange,
                    coerceManualValue = { text ->
                        SettingsFormat.coerceBitrate((text.toIntOrNull() ?: 4) * 1_000_000)
                    },
                    manualSuffix = "Mbps"
                )

                Spacer(Modifier.height(16.dp))
                // FPS Settings & Presets
                SettingSection(
                    title = stringResource(R.string.video_fps_title),
                    subtitle = stringResource(R.string.video_fps_desc),
                    hint = stringResource(R.string.video_fps_hint),
                    currentValueText = stringResource(R.string.fps_value, startupFps),
                    manualSeed = SettingsFormat.manualSeed(startupFps, SettingsFormat.FPS_PRESETS),
                    presets = listOf(
                        stringResource(R.string.preset_fps_20) to 20,
                        stringResource(R.string.preset_fps_24) to 24,
                        stringResource(R.string.preset_fps_30) to 30,
                        stringResource(R.string.preset_fps_60) to 60
                    ),
                    selectedPresetValue = startupFps,
                    onValueChange = onStartupFpsChange,
                    coerceManualValue = { text ->
                        SettingsFormat.coerceFps(text.toIntOrNull() ?: 24)
                    },
                    manualSuffix = "FPS"
                )
            }
        }
    }
}


@Composable
private fun HowToConnect() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.how_to_connect_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(Modifier.height(16.dp))
            ConnectStep("1", stringResource(R.string.how_to_step_1))
            Spacer(Modifier.height(12.dp))
            ConnectStep("2", stringResource(R.string.how_to_step_2))
            Spacer(Modifier.height(12.dp))
            ConnectStep("3", stringResource(R.string.how_to_step_3))
            Spacer(Modifier.height(12.dp))
            ConnectStep("4", stringResource(R.string.how_to_step_4))
        }
    }
}

@Composable
private fun ConnectStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
