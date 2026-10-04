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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dilinkauto.server.R
import com.dilinkauto.server.service.CarConnectionService

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
    val navBarPx = CarConnectionService.navBarWidthPx(density.density, screenWidthPx)
    return with(density) { navBarPx.toDp() }
}

/** The three NavActionButtons (Home, Back, Eject) in a fixed order. */
@Composable
private fun NavActionButtons(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onDisconnect: () -> Unit,
    modifierFor: (Int) -> Modifier
) {
    NavActionButton(
        icon = Icons.Default.Home,
        label = stringResource(R.string.nav_home),
        onClick = onHome,
        modifier = modifierFor(0)
    )
    NavActionButton(
        icon = Icons.Default.ArrowBack,
        label = stringResource(R.string.nav_back),
        onClick = onBack,
        modifier = modifierFor(1)
    )
    NavActionButton(
        icon = Icons.Default.LinkOff,
        label = stringResource(R.string.nav_eject),
        onClick = onDisconnect,
        tint = MaterialTheme.colorScheme.error,
        modifier = modifierFor(2)
    )
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
            .background(Color(0xFF0A0E14))
            .padding(vertical = 12.dp, horizontal = 4.dp)
    ) {
        NavActionButtons(
            onBack = onBack, onHome = onHome, onDisconnect = onDisconnect,
            modifierFor = { Modifier.padding(vertical = 4.dp) }
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
            .background(Color(0xFF0A0E14))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Order is reversed on the bottom bar (Eject, Home, Back) to mirror the
        // rail's top-down order when rotated. modifierFor applies the weight.
        NavActionButton(
            icon = Icons.Default.LinkOff,
            label = stringResource(R.string.nav_eject),
            onClick = onDisconnect,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        NavActionButton(
            icon = Icons.Default.Home,
            label = stringResource(R.string.nav_home),
            onClick = onHome,
            modifier = Modifier.weight(1f)
        )
        NavActionButton(
            icon = Icons.Default.ArrowBack,
            label = stringResource(R.string.nav_back),
            onClick = onBack,
            modifier = Modifier.weight(1f)
        )
    }
}
