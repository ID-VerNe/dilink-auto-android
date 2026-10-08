package com.dilinkauto.server.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarViewport
import com.dilinkauto.server.ui.theme.NavBarBackgroundColor

/**
 * Persistent navigation bar — always visible during streaming mode.
 *
 * Landscape: left rail. Portrait: bottom bar. Both render the same trimmed
 * action set: disconnect (Eject), home, back. The bar width is computed to
 * guarantee an even viewport for the H.264 encoder.
 *
 * [rememberNavBarSize] and [NavActionButtons] are shared between the two
 * layouts so the nav-bar geometry and the button set stay in sync as the
 * viewport rotates.
 */

/** Compute the nav-bar size in dp from the current screen width. */
@Composable
private fun rememberNavBarSize(): Dp {
    val density = LocalDensity.current
    val screenWidthPx = LocalConfiguration.current.screenWidthDp.let {
        (it * density.density).toInt()
    }
    val navBarPx = CarViewport.navBarWidthPx(density.density, screenWidthPx)
    return with(density) { navBarPx.toDp() }
}

/** Stable identity of the three nav actions, independent of display order. */
private enum class NavAction { Home, Back, Eject }

/**
 * The three nav actions (Home, Back, Eject) for both layouts (audit R3-DRY-16).
 *
 * The rail renders them Home/Back/Eject top-down; the bottom bar renders them
 * reversed (Eject/Home/Back) so that rotating the device keeps each button on
 * the same physical side. [modifier] supplies the per-layout sizing (vertical
 * padding for the rail, `weight(1f)` for the bar), so the button set itself
 * stays defined in one place.
 */
@Composable
private fun NavActionButtons(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onDisconnect: () -> Unit,
    reverse: Boolean = false,
    modifier: Modifier = Modifier
) {
    val order = if (reverse) {
        listOf(NavAction.Eject, NavAction.Home, NavAction.Back)
    } else {
        listOf(NavAction.Home, NavAction.Back, NavAction.Eject)
    }
    for (action in order) {
        when (action) {
            NavAction.Home -> NavActionButton(
                icon = Icons.Default.Home,
                label = stringResource(R.string.nav_home),
                onClick = onHome,
                modifier = modifier
            )
            NavAction.Back -> NavActionButton(
                icon = Icons.Default.ArrowBack,
                label = stringResource(R.string.nav_back),
                onClick = onBack,
                modifier = modifier
            )
            NavAction.Eject -> NavActionButton(
                icon = Icons.Default.LinkOff,
                label = stringResource(R.string.nav_eject),
                onClick = onDisconnect,
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier
            )
        }
    }
}

@Composable
fun PersistentNavBar(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val navBarDp = rememberNavBarSize()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        modifier = modifier
            .width(navBarDp)
            .fillMaxHeight()
            .background(NavBarBackgroundColor)
            .padding(vertical = 12.dp, horizontal = 4.dp)
    ) {
        NavActionButtons(
            onBack = onBack, onHome = onHome, onDisconnect = onDisconnect,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

@Composable
fun PersistentBottomNavBar(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val navBarDp = rememberNavBarSize()

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(navBarDp)
            .background(NavBarBackgroundColor)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Reversed order on the bottom bar (Eject, Home, Back) to mirror the
        // rail's top-down order when rotated; weight(1f) spreads the three keys.
        NavActionButtons(
            onBack = onBack, onHome = onHome, onDisconnect = onDisconnect,
            reverse = true,
            modifier = Modifier.weight(1f)
        )
    }
}
