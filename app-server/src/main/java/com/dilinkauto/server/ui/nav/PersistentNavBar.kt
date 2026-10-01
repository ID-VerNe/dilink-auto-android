package com.dilinkauto.server.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dilinkauto.protocol.AppInfo
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService

/**
 * Persistent left-side navigation bar — always visible on all screens.
 *
 * Layout (top to bottom):
 * - Clock (HH:mm)
 * - Network status
 * - Divider
 * - Recent app icons (3-5)
 * - Spacer (fills remaining space)
 * - Divider
 * - Back button
 * - Home button
 */
@Composable
fun PersistentNavBar(
    recentAppsState: RecentAppsState,
    activeAppPackage: String?,
    isPhoneConnected: Boolean,
    appList: List<AppInfo>,
    service: CarConnectionService,
    notificationCount: Int = 0,
    onAppClick: (String) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNotifications: () -> Unit = {},
    onDisconnect: () -> Unit = {}
) {
    val appMap = remember(appList) { appList.associateBy { it.packageName } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenWidthPx = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.let {
        (it * density.density).toInt()
    }
    val navBarPx = com.dilinkauto.server.service.CarConnectionService.navBarWidthPx(density.density, screenWidthPx)
    val navBarDp = with(density) { navBarPx.toDp() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .width(navBarDp)
            .fillMaxHeight()
            .background(Color(0xFF0A0E14))
            .padding(vertical = 12.dp, horizontal = 4.dp)
    ) {
        // Disconnect button
        NavActionButton(
            icon = Icons.Default.LinkOff,
            label = stringResource(R.string.nav_eject),
            onClick = onDisconnect,
            tint = Color(0xFFFF5252),
            modifier = Modifier.weight(1f)
        )

        // Home button
        NavActionButton(
            icon = Icons.Default.Home,
            label = stringResource(R.string.nav_home),
            onClick = onHome,
            modifier = Modifier.weight(1f)
        )

        // Back button
        NavActionButton(
            icon = Icons.Default.ArrowBack,
            label = stringResource(R.string.nav_back),
            onClick = onBack,
            modifier = Modifier.weight(1f)
        )
    }
}
@Composable
fun PersistentBottomNavBar(
    recentAppsState: RecentAppsState,
    activeAppPackage: String?,
    isPhoneConnected: Boolean,
    appList: List<AppInfo>,
    service: CarConnectionService,
    notificationCount: Int = 0,
    onAppClick: (String) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNotifications: () -> Unit = {},
    onDisconnect: () -> Unit = {}
) {
    val appMap = remember(appList) { appList.associateBy { it.packageName } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenWidthPx = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.let {
        (it * density.density).toInt()
    }
    val navBarPx = com.dilinkauto.server.service.CarConnectionService.navBarWidthPx(density.density, screenWidthPx)
    val navBarDp = with(density) { navBarPx.toDp() }

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(navBarDp)
            .background(Color(0xFF0A0E14))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Disconnect button
        NavActionButton(
            icon = Icons.Default.LinkOff,
            label = stringResource(R.string.nav_eject),
            onClick = onDisconnect,
            tint = Color(0xFFFF5252),
            modifier = Modifier.weight(1f)
        )

        // Home button
        NavActionButton(
            icon = Icons.Default.Home,
            label = stringResource(R.string.nav_home),
            onClick = onHome,
            modifier = Modifier.weight(1f)
        )

        // Back button
        NavActionButton(
            icon = Icons.Default.ArrowBack,
            label = stringResource(R.string.nav_back),
            onClick = onBack,
            modifier = Modifier.weight(1f)
        )
    }
}





