package com.dilinkauto.server.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.protocol.AppCategory
import com.dilinkauto.protocol.AppInfo
import com.dilinkauto.protocol.MediaAction
import com.dilinkauto.protocol.PlaybackState
import com.dilinkauto.server.R
import com.dilinkauto.server.ServerApp
import com.dilinkauto.server.service.CarConnectionService
import com.dilinkauto.server.ui.theme.CommunicationColor
import com.dilinkauto.server.ui.theme.MusicColor
import com.dilinkauto.server.ui.theme.NavigationColor
import com.dilinkauto.server.ui.theme.OtherColor
import kotlin.math.max

/**
 * Home screen content: app grid (or connection status) + now playing bar.
 * No navigation controls — those are in the persistent nav bar.
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
        if (state == CarConnectionService.State.STREAMING && appList.isEmpty()) {
            // Connected but no apps in the allowlist — show an explanatory empty state
            // instead of the connection screen, which would imply the link is down.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.home_allowlist_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else if (state == CarConnectionService.State.STREAMING && appList.isNotEmpty()) {
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

/**
 * Stable wrapper for [AppTile] inputs. [AppInfo] carries an unstable [ByteArray]
 * (iconPng), which makes AppTile non-skippable and forces full-grid recomposition
 * on any appList reassignment. This wrapper excludes the byte array — AppTile
 * reads the prepared icon from the cache by packageName, so the wrapper's identity
 * is the only input that matters for recomposition.
 */
@Immutable
data class AppTileData(
    val packageName: String,
    val appName: String,
    val category: AppCategory,
    val isPinned: Boolean
)

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

    val context = LocalContext.current
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
                            data = AppTileData(
                                packageName = app.packageName,
                                appName = app.appName,
                                category = app.category,
                                isPinned = pinnedApps.contains(app.packageName)
                            ),
                            onClick = { onAppClick(app.packageName) },
                            onUninstall = { onUninstall(app.packageName) },
                            onAppInfo = { onAppInfo(app.packageName) },
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
    data: AppTileData,
    onClick: () -> Unit,
    onUninstall: () -> Unit,
    onAppInfo: () -> Unit,
    onTogglePin: () -> Unit = {}
) {
    val categoryIcon = when (data.category) {
        AppCategory.NAVIGATION -> Icons.Default.Navigation
        AppCategory.MUSIC -> Icons.Default.MusicNote
        AppCategory.COMMUNICATION -> Icons.Default.Chat
        AppCategory.OTHER -> Icons.Default.Apps
    }

    val categoryColor = when (data.category) {
        AppCategory.NAVIGATION -> NavigationColor
        AppCategory.MUSIC -> MusicColor
        AppCategory.COMMUNICATION -> CommunicationColor
        AppCategory.OTHER -> OtherColor
    }

    // O(1) HashMap lookup — bitmap prepared by prepareAll() before grid renders.
    // Re-keyed on preparedVersion so a fresh prepareAll() pass (e.g. after icon
    // updates) triggers recomposition and the new bitmap is picked up.
    val preparedVersion = ServerApp.iconCache.preparedVersion
    val iconBitmap = remember(preparedVersion, data.packageName) {
        ServerApp.iconCache.getPrepared(data.packageName)
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
                contentDescription = data.appName,
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
            text = data.appName,
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
                    Text(if (data.isPinned) stringResource(R.string.unpin_from_top) else stringResource(R.string.pin_to_top), color = Color.White, fontSize = 18.sp)
                },
                onClick = {
                    menuExpanded = false
                    onTogglePin()
                },
                leadingIcon = {
                    Icon(
                        if (data.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
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
