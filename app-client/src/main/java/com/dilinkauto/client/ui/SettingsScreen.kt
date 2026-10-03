package com.dilinkauto.client.ui

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.client.R
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.client.service.ConnectionService
import com.dilinkauto.client.service.DistributionChannel
import com.dilinkauto.client.service.UpdateManager
import com.dilinkauto.client.service.UpdateState
import android.content.Intent
import android.net.Uri

/**
 * Settings screen: permissions, distribution channel, updates, debug, about.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAllFilesAccess: () -> Unit,
    onOpenBatteryExemption: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenAllowlist: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    val context = LocalContext.current
    val pkg = context.packageName
    var permissionsKey by remember { mutableIntStateOf(0) }

    // Periodic re-check
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            permissionsKey++
        }
    }

    // Permission checks
    val hasAllFiles = remember(permissionsKey) { PermissionChecker.hasAllFilesAccess() }
    val hasBattery = remember(permissionsKey) { PermissionChecker.hasBatteryExemption(context, pkg) }
    val hasAccessibility = remember(permissionsKey) { PermissionChecker.hasAccessibility(context, pkg) }
    var logEnabled by remember {
        mutableStateOf(context.getSharedPreferences(com.dilinkauto.protocol.AppPrefs.FILE_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(com.dilinkauto.protocol.AppPrefs.LOG_ENABLED, true))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Fixed header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(stringResource(R.string.settings_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // Permissions
            Text(stringResource(R.string.settings_permissions), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp))

            SetupItem(
                icon = if (hasAllFiles) Icons.Default.CheckCircle else Icons.Default.Folder,
                title = if (hasAllFiles) "${stringResource(R.string.perm_all_files)} ✓" else stringResource(R.string.perm_all_files),
                description = if (hasAllFiles) stringResource(R.string.perm_granted) else stringResource(R.string.perm_all_files_granted),
                onClick = onOpenAllFilesAccess
            )
            Spacer(Modifier.height(8.dp))

            SetupItem(
                icon = if (hasBattery) Icons.Default.CheckCircle else Icons.Default.BatterySaver,
                title = if (hasBattery) "${stringResource(R.string.perm_battery)} ✓" else stringResource(R.string.perm_battery),
                description = if (hasBattery) stringResource(R.string.perm_granted) else stringResource(R.string.perm_battery_granted),
                onClick = onOpenBatteryExemption
            )
            Spacer(Modifier.height(8.dp))

            SetupItem(
                icon = if (hasAccessibility) Icons.Default.CheckCircle else Icons.Default.TouchApp,
                title = if (hasAccessibility) "${stringResource(R.string.perm_accessibility)} ✓" else stringResource(R.string.perm_accessibility),
                description = if (hasAccessibility) stringResource(R.string.perm_granted) else stringResource(R.string.perm_accessibility_granted),
                onClick = onOpenAccessibility
            )
            Spacer(Modifier.height(8.dp))

            SetupItem(
                icon = Icons.Default.Usb,
                title = stringResource(R.string.perm_usb_debugging),
                description = stringResource(R.string.perm_usb_desc),
                onClick = onOpenDeveloperOptions
            )

            Spacer(Modifier.height(8.dp))

            SetupItem(
                icon = Icons.Default.Apps,
                title = stringResource(R.string.allowlist_title),
                description = stringResource(R.string.allowlist_desc),
                onClick = onOpenAllowlist
            )

            Spacer(Modifier.height(8.dp))

            // Shizuku
            val shizukuInstalled = remember(permissionsKey) { ShizukuManager.isInstalled }
            val shizukuAvailable = remember(permissionsKey) { ShizukuManager.isAvailable }
            val shizukuIcon = when {
                shizukuAvailable -> Icons.Default.Shield
                shizukuInstalled -> Icons.Default.Security
                else -> Icons.Default.Info
            }
            val shizukuTitle = when {
                shizukuAvailable -> stringResource(R.string.perm_shizuku_available)
                shizukuInstalled -> stringResource(R.string.perm_shizuku_needs_permission)
                else -> stringResource(R.string.perm_shizuku)
            }
            val shizukuDesc = when {
                shizukuAvailable -> stringResource(R.string.perm_shizuku_granted)
                shizukuInstalled -> stringResource(R.string.perm_shizuku_permission_desc)
                else -> stringResource(R.string.perm_shizuku_desc)
            }
            SetupItem(
                icon = shizukuIcon,
                title = shizukuTitle,
                description = shizukuDesc,
                onClick = {
                    when {
                        shizukuAvailable -> { /* already authorized */ }
                        shizukuInstalled -> {
                            ShizukuManager.requestPermission()
                            ShizukuManager.openShizukuApp(context)
                            permissionsKey++
                        }
                    }
                }
            )

            Spacer(Modifier.height(32.dp))

            // Distribution Channel
            Text(stringResource(R.string.settings_distribution), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp))

            ChannelSelectorCard()

            Spacer(Modifier.height(32.dp))

            // Updates
            Text(stringResource(R.string.updates_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp))

            UpdatesCard(
                onCheckForUpdate = onCheckForUpdate,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdate = onInstallUpdate
            )

            Spacer(Modifier.height(32.dp))

            // Debug
            Text(stringResource(R.string.settings_debug), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.log_enabled), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.log_enabled_desc), fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = logEnabled,
                        onCheckedChange = { enabled ->
                            logEnabled = enabled
                            ConnectionService.setLogEnabled(context, enabled)
                        }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // About
            Text(stringResource(R.string.about_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray,
                modifier = Modifier.padding(bottom = 12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val versionName = com.dilinkauto.client.service.AppVersion.nameOrEmpty(context).ifEmpty { "unknown" }
                    Text(stringResource(R.string.about_version, versionName), fontWeight = FontWeight.Medium, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.about_tagline), fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.about_dev_credit), fontSize = 12.sp, color = Color(0xFFB0BEC5))
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andersonlucasg3"))
                        context.startActivity(intent)
                    }) {
                        Text(stringResource(R.string.about_dev_github), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.about_libs_heading), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFB0BEC5))
                    Text(stringResource(R.string.about_lib_dadb), fontSize = 12.sp, color = Color.Gray)
                    Text(stringResource(R.string.about_lib_compose), fontSize = 12.sp, color = Color.Gray)
                    Text(stringResource(R.string.about_lib_coroutines), fontSize = 12.sp, color = Color.Gray)
                    Text(stringResource(R.string.about_lib_scrcpy), fontSize = 12.sp, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun SetupItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, color = Color.White)
                Text(description, fontSize = 12.sp, color = Color.Gray)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}

@Composable
fun UpdatesCard(
    onCheckForUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    val updateState by UpdateManager.updateState.collectAsState()
    val downloadProgress by UpdateManager.downloadProgress.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.updates_title), fontWeight = FontWeight.Medium, color = Color.White)

            Spacer(Modifier.height(8.dp))
            when (val state = updateState) {
                is UpdateState.Idle -> {
                    TextButton(onClick = onCheckForUpdate) {
                        Text(stringResource(R.string.updates_check_phone), color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UpdateState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.updates_checking), fontSize = 13.sp, color = Color.Gray)
                    }
                }
                is UpdateState.UpToDate -> {
                    Text(stringResource(R.string.updates_up_to_date, state.version), fontSize = 13.sp, color = Color(0xFF4CAF50))
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onCheckForUpdate) {
                        Text(stringResource(R.string.updates_check_phone), color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UpdateState.Available -> {
                    Text(stringResource(R.string.updates_available, state.version), fontSize = 13.sp, color = Color(0xFFFFA726))
                    val sizeMb = state.sizeBytes / (1024.0 * 1024.0)
                    Text(stringResource(R.string.updates_size_mb, sizeMb), fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownloadUpdate, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.updates_download_btn))
                    }
                }
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = downloadProgress / 100f, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = Color(0xFF30363D))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.updates_downloading, downloadProgress), fontSize = 13.sp, color = Color.Gray)
                }
                is UpdateState.ReadyToInstall -> {
                    Text(stringResource(R.string.updates_ready, state.version), fontSize = 13.sp, color = Color(0xFF4CAF50))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onInstallUpdate, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                        Icon(androidx.compose.material.icons.Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.updates_install_btn))
                    }
                }
                is UpdateState.Installing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.updates_installing, state.version), fontSize = 13.sp, color = Color.Gray)
                    }
                }
                is UpdateState.Installed -> {
                    Text(stringResource(R.string.updates_installed), fontSize = 13.sp, color = Color(0xFF4CAF50))
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onCheckForUpdate) {
                        Text(stringResource(R.string.updates_check_phone), color = MaterialTheme.colorScheme.primary)
                    }
                }
                is UpdateState.Error -> {
                    Text(state.message, fontSize = 12.sp, color = Color(0xFFEF5350))
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onCheckForUpdate) { Text(stringResource(R.string.updates_retry), color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
fun ChannelSelectorCard() {
    val currentChannel by remember { mutableStateOf(UpdateManager.channel) }
    var selected by remember { mutableStateOf(currentChannel) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.channel_title), fontWeight = FontWeight.Medium, color = Color.White)
            Text(
                when (selected) {
                    DistributionChannel.RELEASE -> stringResource(R.string.channel_release_desc)
                    DistributionChannel.PRE_RELEASE -> stringResource(R.string.channel_prerelease_desc)
                },
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selected == DistributionChannel.RELEASE,
                    onClick = {
                        selected = DistributionChannel.RELEASE
                        UpdateManager.channel = DistributionChannel.RELEASE
                    },
                    label = { Text(stringResource(R.string.channel_release)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF1A73E8)
                    )
                )
                FilterChip(
                    selected = selected == DistributionChannel.PRE_RELEASE,
                    onClick = {
                        selected = DistributionChannel.PRE_RELEASE
                        UpdateManager.channel = DistributionChannel.PRE_RELEASE
                    },
                    label = { Text(stringResource(R.string.channel_prerelease)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFFFA726)
                    )
                )
            }
        }
    }
}
