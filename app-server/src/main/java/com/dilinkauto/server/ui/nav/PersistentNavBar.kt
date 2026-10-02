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
 * Persistent navigation bar — always visible during streaming mode.
 *
 * Landscape: left rail. Portrait: bottom bar. Both render the same action set:
 * eject (disconnect), recent-apps rail, home, back, notifications (with badge).
 * The landscape rail additionally shows a clock and network indicator at the top.
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
    val recentApps = recentAppsState.recentApps
    val shortcutsEnabled = false // see NavBarComponents.RecentAppIcon / issue #57

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(navBarDp)
            .fillMaxHeight()
            .background(Color(0xFF0A0E14))
            .padding(vertical = 12.dp, horizontal = 4.dp)
    ) {
        ClockDisplay()
        NetworkInfo(isConnected = isPhoneConnected)
        Divider(color = Color(0xFF2A2F3A), thickness = 1.dp, modifier = Modifier.fillMaxWidth())

        // Recent-apps rail — one tile per recently launched app that is still installed.
        for (pkg in recentApps) {
            val app = appMap[pkg] ?: continue
            RecentAppIcon(
                app = app,
                isActive = pkg == activeAppPackage,
                service = service,
                onClick = { onAppClick(pkg) }
            )
        }

        Spacer(Modifier.weight(1f))

        Divider(color = Color(0xFF2A2F3A), thickness = 1.dp, modifier = Modifier.fillMaxWidth())

        // Notifications button with badge
        NotificationsButton(
            count = notificationCount,
            onClick = onNotifications
        )

        // Home button
        NavActionButton(
            icon = Icons.Default.Home,
            label = stringResource(R.string.nav_home),
            onClick = onHome,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Back button
        NavActionButton(
            icon = Icons.Default.ArrowBack,
            label = stringResource(R.string.nav_back),
            onClick = onBack,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // Disconnect button
        NavActionButton(
            icon = Icons.Default.LinkOff,
            label = stringResource(R.string.nav_eject),
            onClick = onDisconnect,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 4.dp)
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
    val recentApps = recentAppsState.recentApps

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
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )

        // Recent-apps rail — inline along the bottom bar.
        for (pkg in recentApps) {
            val app = appMap[pkg] ?: continue
            RecentAppIcon(
                app = app,
                isActive = pkg == activeAppPackage,
                service = service,
                onClick = { onAppClick(pkg) },
                modifier = Modifier.weight(1f)
            )
        }

        // Notifications button with badge
        NotificationsButton(
            count = notificationCount,
            onClick = onNotifications,
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





