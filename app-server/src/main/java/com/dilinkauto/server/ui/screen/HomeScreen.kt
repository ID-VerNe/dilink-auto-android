package com.dilinkauto.server.ui.screen

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.protocol.*
import com.dilinkauto.server.R
import com.dilinkauto.server.ServerApp
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.theme.*
import kotlin.math.max

/**
 * Home screen content: app grid (or connection status) + now playing bar.
 * No navigation controls — those are in the persistent nav bar.
 *
 * Formerly `LauncherScreen.kt` hosted both a dead top-level entry (`LauncherScreen`,
 * `CarStatusBar`, `SideNavBar`, `NavButton`) and these live composables. The dead
 * shell was removed; the live composables moved here so the home screen has a single
 * source of truth.
 */
@Composable
fun HomeContent(
    service: CarConnectionService,
    onAppClick: (String) -> Unit
) {
    val state by service.state.collectAsState()
    val appList by service.appList.collectAsState()
    val mediaMetadata by service.mediaMetadata.collectAsState()
    val playbackState by service.playbackState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // Main content
        if (state == CarConnectionService.State.STREAMING && appList.isNotEmpty()) {
            AppGrid(
                apps = appList,
                onAppClick = onAppClick,
                onUninstall = { pkg -> service.requestUninstall(pkg) },
                onAppInfo = { pkg -> service.requestAppInfo(pkg) },
                modifier = Modifier.weight(1f)
            )
        } else {
            ConnectionStatus(
                state = state,
                modifier = Modifier.weight(1f),
                onManualConnect = { ip -> service.connectManual(ip) }
            )
        }

        // Now playing bar
        mediaMetadata?.let { metadata ->
            NowPlayingBar(
                metadata = metadata,
                playbackState = playbackState,
                onPlayPause = {
                    val action = if (playbackState?.state == PlaybackState.PLAYING)
                        MediaAction.PAUSE else MediaAction.PLAY
                    service.sendMediaAction(action)
                },
                onNext = { service.sendMediaAction(MediaAction.NEXT) },
                onPrevious = { service.sendMediaAction(MediaAction.PREVIOUS) }
            )
        }
    }
}

@Composable
fun AppGrid(
    apps: List<AppInfo>,
    onAppClick: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("dilinkauto_pinned", android.content.Context.MODE_PRIVATE) }
    var pinnedApps by remember {
        mutableStateOf(prefs.getStringSet("pinned_apps", emptySet())?.toSet() ?: emptySet())
    }

    val togglePin: (String) -> Unit = { pkg ->
        val newPinned = if (pinnedApps.contains(pkg)) pinnedApps - pkg else pinnedApps + pkg
        pinnedApps = newPinned
        prefs.edit().putStringSet("pinned_apps", newPinned).apply()
    }

    val filteredApps = remember(apps, searchQuery, pinnedApps) {
        val distinct = apps.distinctBy { it.packageName }
        // Pre-compute the lowercase sort key once per app rather than on every
        // comparison during the sort.
        val withSortKey = distinct.map { it to it.appName.lowercase() }
        val sorted = withSortKey.sortedWith(
            compareByDescending<Pair<AppInfo, String>> { pinnedApps.contains(it.first.packageName) }
                .thenBy { it.second }
        ).map { it.first }
        if (searchQuery.isBlank()) sorted
        else sorted.filter { it.appName.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = modifier) {
        Row(modifier = Modifier.weight(1f)) {
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                // Fixed columns calculated from available width — same density as
                // Adaptive(100.dp) but without the runtime measurement crash risk
                val gridColumns = max(3, (maxWidth / 100.dp).toInt().coerceAtMost(12))

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(gridColumns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 24.dp, top = 24.dp, end = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredApps, key = { it.packageName }, contentType = { "app_tile" }) { app ->
                        AppTile(
                            app = app,
                            onClick = { onAppClick(app.packageName) },
                            onUninstall = { onUninstall(app.packageName) },
                            onAppInfo = { onAppInfo(app.packageName) },
                            isPinned = pinnedApps.contains(app.packageName),
                            onTogglePin = { togglePin(app.packageName) }
                        )
                    }
                }
            }

        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { newValue ->
                searchQuery = newValue
            },
            placeholder = { Text(stringResource(R.string.search_apps)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .imePadding(),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color(0xFF2A2F3A),
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )


    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    app: AppInfo,
    onClick: () -> Unit,
    onUninstall: () -> Unit,
    onAppInfo: () -> Unit,
    isPinned: Boolean = false,
    onTogglePin: () -> Unit = {}
) {
    val categoryIcon = when (app.category) {
        AppCategory.NAVIGATION -> Icons.Default.Navigation
        AppCategory.MUSIC -> Icons.Default.MusicNote
        AppCategory.COMMUNICATION -> Icons.Default.Chat
        AppCategory.OTHER -> Icons.Default.Apps
    }

    val categoryColor = when (app.category) {
        AppCategory.NAVIGATION -> NavigationColor
        AppCategory.MUSIC -> MusicColor
        AppCategory.COMMUNICATION -> CommunicationColor
        AppCategory.OTHER -> OtherColor
    }

    // O(1) HashMap lookup — bitmap prepared by prepareAll() before grid renders.
    // Re-keyed on preparedVersion so a fresh prepareAll() pass (e.g. after icon
    // updates) triggers recomposition and the new bitmap is picked up.
    val preparedVersion = ServerApp.iconCache.preparedVersion
    val iconBitmap = remember(preparedVersion, app.packageName) {
        ServerApp.iconCache.getPrepared(app.packageName)
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = app.appName,
                modifier = Modifier
                    .size(64.dp)
            )
        } else {
            Icon(
                categoryIcon,
                contentDescription = null,
                tint = categoryColor,
                modifier = Modifier.size(64.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = app.appName,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    // Dropdown created lazily — only composed when a long-click opens it
    if (menuExpanded) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = { menuExpanded = false },
            offset = DpOffset(8.dp, 0.dp),
            modifier = Modifier
                .widthIn(min = 220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            DropdownMenuItem(
                text = {
                    Text(if (isPinned) stringResource(R.string.unpin_from_top) else stringResource(R.string.pin_to_top), color = Color.White, fontSize = 18.sp)
                },
                onClick = {
                    menuExpanded = false
                    onTogglePin()
                },
                leadingIcon = {
                    Icon(
                        if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                        null,
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(28.dp)
                    )
                },
                modifier = Modifier.padding(vertical = 4.dp)
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.action_uninstall), color = Color.White, fontSize = 18.sp)
                },
                onClick = {
                    menuExpanded = false
                    onUninstall()
                },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                },
                modifier = Modifier.padding(vertical = 4.dp)
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.action_app_info), color = Color.White, fontSize = 18.sp)
                },
                onClick = {
                    menuExpanded = false
                    onAppInfo()
                },
                leadingIcon = {
                    Icon(Icons.Default.Info, null, tint = Color(0xFF64B5F6), modifier = Modifier.size(28.dp))
                },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
fun ConnectionStatus(
    state: CarConnectionService.State,
    modifier: Modifier,
    onManualConnect: (String) -> Unit = {}
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 500.dp)
        ) {
            when (state) {
                CarConnectionService.State.IDLE -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.searching_for_phone),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.phone_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    // Manual connect option
                    Spacer(Modifier.height(32.dp))
                    ManualConnectBox(onConnect = onManualConnect)
                }
                CarConnectionService.State.CONNECTING -> {
                    CircularProgressIndicator(
                        color = Color(0xFFFFA726),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.connecting),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
                CarConnectionService.State.CONNECTED -> {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.connected_waiting_for_data),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
                CarConnectionService.State.STREAMING -> {
                    // Won't reach here since we show AppGrid when streaming
                }
            }
        }
    }
}

@Composable
fun ManualConnectBox(onConnect: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("dilinkauto", android.content.Context.MODE_PRIVATE) }
    val savedIp = remember { prefs.getString("last_manual_ip", null) }
    val gatewayIp = remember {
        try {
            val wm = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val gw = wm.dhcpInfo.gateway
            if (gw != 0) String.format("%d.%d.%d.%d", gw and 0xFF, (gw shr 8) and 0xFF, (gw shr 16) and 0xFF, (gw shr 24) and 0xFF)
            else ""
        } catch (_: Exception) { "" }
    }
    var ipAddress by remember {
        mutableStateOf(savedIp ?: gatewayIp.ifEmpty { "192.168.43.1" })
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                stringResource(R.string.manual_connect_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text(stringResource(R.string.manual_connect_ip_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        val ip = ipAddress.trim()
                        if (ip.isNotBlank()) {
                            prefs.edit().putString("last_manual_ip", ip).apply()
                            onConnect(ip)
                        }
                    },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.manual_connect_button))
                }
            }
        }
    }
}

@Composable
fun NowPlayingBar(
    metadata: MediaMetadata,
    playbackState: PlaybackState?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0E14))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Track info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                metadata.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${metadata.artist} — ${metadata.album}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Controls — large touch targets for car use
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Icon(
                    if (playbackState?.state == PlaybackState.PLAYING)
                        Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Play/Pause",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}
