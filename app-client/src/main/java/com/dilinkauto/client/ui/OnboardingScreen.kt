package com.dilinkauto.client.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dilinkauto.client.R
import com.dilinkauto.client.service.InstallStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val actionLabel: String,
    val isGranted: () -> Boolean,
    val onAction: () -> Unit,
    val prerequisites: List<String> = emptyList()
)

/**
 * Onboarding flow: welcome, all-files access, battery exemption, accessibility,
 * car setup, done. Re-checks permissions on resume and polls while waiting.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit, onInstallOnCar: () -> Unit, installStatus: String) {
    val context = LocalContext.current
    val pkg = context.packageName
    val scope = rememberCoroutineScope()

    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Re-check permissions instantly when returning from settings (handles both
    // full activities like Accessibility and dialogs like Battery Optimization)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Direct reads — no remember(), so they re-evaluate on every recomposition
    val hasAllFiles = PermissionChecker.hasAllFilesAccess()
    val hasBattery = PermissionChecker.hasBatteryExemption(context, pkg)
    val hasAccessibility = PermissionChecker.hasAccessibility(context, pkg)

    // Poll a specific permission directly from the system API (bypasses any caching)
    fun pollPermission(stepIndex: Int) {
        scope.launch {
            for (i in 0..30) {
                delay(300)
                val granted = when (stepIndex) {
                    1 -> PermissionChecker.hasAllFilesAccess()
                    2 -> PermissionChecker.hasBatteryExemption(context, pkg)
                    3 -> PermissionChecker.hasAccessibility(context, pkg)
                    else -> true
                }
                if (granted) {
                    refreshKey++
                    break
                }
            }
        }
    }

    // Resolve strings outside remember to avoid crossinline restriction
    val welcomeTitle = stringResource(R.string.onboarding_welcome_title)
    val welcomeDesc = stringResource(R.string.onboarding_welcome_desc)
    val welcomeAction = stringResource(R.string.onboarding_continue)
    val filesTitle = stringResource(R.string.onboarding_files_title)
    val filesDesc = stringResource(R.string.onboarding_files_desc)
    val batteryTitle = stringResource(R.string.onboarding_battery_title)
    val batteryDesc = stringResource(R.string.onboarding_battery_desc)
    val accessibilityTitle = stringResource(R.string.onboarding_accessibility_title)
    val accessibilityDesc = stringResource(R.string.onboarding_accessibility_desc)
    val carSetupTitle = stringResource(R.string.onboarding_car_setup_title)
    val carSetupDesc = stringResource(R.string.onboarding_car_setup_desc)
    val carSetupContinue = stringResource(R.string.onboarding_continue)
    val carInstallBtn = stringResource(R.string.onboarding_car_install_btn)
    val carSkipBtn = stringResource(R.string.onboarding_skip_btn)
    val carPrereqWifiAdb = stringResource(R.string.onboarding_car_prereq_wifi_adb)
    val carPrereqHotspot = stringResource(R.string.onboarding_car_prereq_hotspot)
    val carPrereqConnected = stringResource(R.string.onboarding_car_prereq_connected)
    val carPrereqInstalled = stringResource(R.string.onboarding_car_prereq_installed)
    val doneTitle = stringResource(R.string.onboarding_done_title)
    val doneDesc = stringResource(R.string.onboarding_done_desc)
    val doneAction = stringResource(R.string.onboarding_start)
    val grantLabel = stringResource(R.string.onboarding_grant)

    val steps = remember(hasAllFiles, hasBattery, hasAccessibility, refreshKey) {
        listOf(
            OnboardingStep(
                icon = Icons.Default.CarRepair,
                title = welcomeTitle, description = welcomeDesc,
                actionLabel = welcomeAction,
                isGranted = { true }, onAction = {}
            ),
            OnboardingStep(
                icon = Icons.Default.Folder,
                title = filesTitle, description = filesDesc,
                actionLabel = grantLabel,
                isGranted = { hasAllFiles },
                onAction = {
                    if (Build.VERSION.SDK_INT >= 30 && !PermissionChecker.hasAllFilesAccess()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            ),
            OnboardingStep(
                icon = Icons.Default.BatterySaver,
                title = batteryTitle, description = batteryDesc,
                actionLabel = grantLabel,
                isGranted = { hasBattery },
                onAction = {
                    if (!PermissionChecker.hasBatteryExemption(context, pkg)) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:$pkg")
                                }
                            )
                        } catch (_: Exception) {}
                    }
                }
            ),
            OnboardingStep(
                icon = Icons.Default.TouchApp,
                title = accessibilityTitle, description = accessibilityDesc,
                actionLabel = grantLabel,
                isGranted = { hasAccessibility },
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            ),
            OnboardingStep(
                icon = Icons.Default.DirectionsCar,
                title = carSetupTitle, description = carSetupDesc,
                actionLabel = carSetupContinue,
                isGranted = { true }, onAction = {},
                prerequisites = listOf(carPrereqWifiAdb, carPrereqHotspot, carPrereqConnected, carPrereqInstalled)
            ),
            OnboardingStep(
                icon = Icons.Default.CheckCircle,
                title = doneTitle, description = doneDesc,
                actionLabel = doneAction,
                isGranted = { true }, onAction = {}
            )
        )
    }

    val step = steps[currentStep]

    // Auto-advance if current permission is already granted (skip welcome and car setup steps)
    LaunchedEffect(refreshKey, currentStep) {
        if (currentStep > 0 && currentStep != 4 && currentStep < steps.lastIndex && step.isGranted()) {
            delay(300)
            currentStep++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117))
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Progress dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            steps.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .size(if (index == currentStep) 10.dp else 8.dp)
                        .background(
                            if (index <= currentStep) MaterialTheme.colorScheme.primary
                            else Color(0xFF30363D),
                            RoundedCornerShape(50)
                        )
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        // Icon
        AnimatedContent(targetState = step.icon, label = "icon") { icon ->
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = if (step.isGranted() && currentStep > 0) Color(0xFF4CAF50)
                       else MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(32.dp))

        // Title
        AnimatedContent(targetState = step.title, label = "title") { title ->
            Text(
                title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(12.dp))

        // Description
        AnimatedContent(targetState = step.description, label = "desc") { desc ->
            Text(
                desc,
                fontSize = 15.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }

        if (currentStep > 0 && currentStep != 4 && step.isGranted()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboarding_granted_label), fontSize = 14.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
        }

        // Car setup step: prerequisites + install button + skip
        if (currentStep == 4) {
            Spacer(Modifier.height(16.dp))

            // Prerequisite items
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                step.prerequisites.forEachIndexed { index, prereq ->
                    val icon = when (index) {
                        0 -> Icons.Default.Build
                        1 -> Icons.Default.Wifi
                        2 -> Icons.Default.Link
                        3 -> Icons.Default.Download
                        else -> Icons.Default.CheckCircle
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = Color(0xFF8AB4F8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            prereq,
                            fontSize = 14.sp,
                            color = Color(0xFFB0BEC5)
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            CarSetupInstallSection(
                installStatus = installStatus,
                onInstallOnCar = onInstallOnCar,
                onSkip = { currentStep++ },
                carInstallBtn = carInstallBtn,
                carSkipBtn = carSkipBtn
            )

            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(48.dp))

        // Action button
        if (currentStep < steps.lastIndex) {
            Button(
                onClick = {
                    if (step.isGranted()) {
                        currentStep++
                    } else {
                        step.onAction()
                        pollPermission(currentStep)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (step.isGranted()) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (step.isGranted()) Icons.Default.CheckCircle else Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (step.isGranted()) stringResource(R.string.onboarding_continue) else step.actionLabel,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Skip button (not for welcome step)
            if (currentStep > 0 && !step.isGranted()) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { currentStep++ }) {
                    Text(stringResource(R.string.onboarding_skip_btn), color = Color.Gray)
                }
            }
        } else {
            // Done step
            Button(
                onClick = onComplete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text(step.actionLabel, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * The car-setup step's install-status section: shows the live install status
 * from [ConnectionService.installStatusFlow] with the appropriate action button
 * (install / retry on auth / retry on error / skip while installing).
 */
@Composable
private fun CarSetupInstallSection(
    installStatus: String,
    onInstallOnCar: () -> Unit,
    onSkip: () -> Unit,
    carInstallBtn: String,
    carSkipBtn: String
) {
    val status = InstallStatus.parse(installStatus)
    val stageIndex = InstallStatus.stageIndex(installStatus)

    when (status) {
        InstallStatus.DONE -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(installStatus, fontSize = 14.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
        }
        InstallStatus.AUTH_NEEDED -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFA726), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(installStatus, fontSize = 13.sp, color = Color(0xFFFFA726))
            }
            Spacer(Modifier.height(8.dp))
            InstallRetryButton(onInstallOnCar, stringResource(R.string.onboarding_continue))
        }
        InstallStatus.ERROR -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(installStatus, fontSize = 14.sp, color = Color(0xFFEF5350))
            }
            Spacer(Modifier.height(8.dp))
            InstallRetryButton(onInstallOnCar, stringResource(R.string.car_app_retry))
        }
        else -> {
            // In-progress (searching/connecting/pushing/installing/launching) or idle.
            if (status.isInProgress) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2332))
                ) {
                    InstallStageProgress(installStatus, stageIndex)
                }
                Spacer(Modifier.height(8.dp))
                Text(carSkipBtn, fontSize = 13.sp, color = Color.Gray)
            } else {
                // Idle — offer the initial install button.
                Button(
                    onClick = onInstallOnCar,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                ) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(carInstallBtn, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (!status.isInProgress) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text(carSkipBtn, color = Color.Gray)
        }
    }
}

@Composable
private fun InstallRetryButton(onInstallOnCar: () -> Unit, label: String) {
    Button(
        onClick = onInstallOnCar,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Stage checklist shown during install. Shared with the legacy
 * [InstallStatusCard] (now removed) — kept here because onboarding is the
 * only remaining caller of the in-card stage progress visualization.
 */
@Composable
fun InstallStageProgress(status: String, stageIndex: Int = InstallStatus.stageIndex(status)) {
    Column(modifier = Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(status, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
        if (stageIndex >= 0) {
            Spacer(Modifier.height(10.dp))
            InstallStatus.stageKeywords.forEachIndexed { index, (_, labelRes) ->
                val stageState = when {
                    index < stageIndex -> "done"
                    index == stageIndex -> "active"
                    else -> "pending"
                }
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        when (stageState) {
                            "done" -> Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = Color(0xFF4CAF50), modifier = Modifier.size(12.dp))
                            "active" -> CircularProgressIndicator(
                                modifier = Modifier.size(12.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary)
                            "pending" -> Box(modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF30363D), RoundedCornerShape(3.dp)))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(labelRes),
                        fontSize = 12.sp,
                        color = when (stageState) {
                            "done" -> Color(0xFF4CAF50)
                            "active" -> Color.White
                            else -> Color(0xFF757575)
                        }
                    )
                }
            }
        }
    }
}
