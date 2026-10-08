package com.dilinkauto.client

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dilinkauto.client.service.ConnectionService
import com.dilinkauto.client.ui.DiLinkAutoTheme
import com.dilinkauto.client.ui.MainScreen
import com.dilinkauto.client.ui.OnboardingScreen
import com.dilinkauto.client.ui.PermissionIntents
import com.dilinkauto.client.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) { prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply() }

    /**
     * Top-level screen routing (audit R3-SRP-09) — replaces the previous
     * onboarding/settings/allowlist boolean trio with a single sealed state.
     */
    private sealed interface Screen {
        data object Onboarding : Screen
        data object Main : Screen
        data object Settings : Screen
        data object Allowlist : Screen
    }

    private var screen by mutableStateOf<Screen>(Screen.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screen lock while streaming — HyperOS may override system timeout settings
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        screen = if (onboardingCompleted) Screen.Main else Screen.Onboarding

        // Auto-start the service when the app is opened (e.g. by the car via USB ADB).
        // Only if onboarding is done and the service isn't already running — calling
        // startForegroundService on an already-running service is harmless but noisy.
        if (onboardingCompleted && ConnectionService.serviceState.value == ConnectionService.State.IDLE) {
            startConnectionService()
        }

        setContent {
            DiLinkAutoTheme {
                val installStatus by ConnectionService.installStatusFlow.collectAsState()
                when (screen) {
                    Screen.Onboarding -> OnboardingScreen(
                        onComplete = {
                            onboardingCompleted = true
                            screen = Screen.Main
                        },
                        onInstallOnCar = { installOnCar(null) },
                        installStatus = installStatus
                    )
                    Screen.Allowlist -> AllowlistScreen(onBack = { screen = Screen.Main })
                    Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Main },
                        onOpenAllFilesAccess = { PermissionIntents.openAllFilesAccess(this) },
                        onOpenBatteryExemption = { PermissionIntents.openBatteryExemption(this) },
                        onOpenAccessibility = { PermissionIntents.openAccessibilitySettings(this) },
                        onOpenDeveloperOptions = { PermissionIntents.openDeveloperOptions(this) },
                        onOpenAllowlist = { screen = Screen.Allowlist }
                    )
                    Screen.Main -> MainScreen(
                        onStartService = { startConnectionService() },
                        onStopService = { stopConnectionService() },
                        onInstallOnCar = { ip -> installOnCar(ip) },
                        onOpenSettings = { screen = Screen.Settings },
                        onShareLogs = { LogSharer.share(this) }
                    )
                }
            }
        }
    }

    private fun startConnectionService() {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopConnectionService() {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_STOP
        }
        startService(intent)
    }

    private fun installOnCar(ip: String? = null) {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_INSTALL_CAR
            if (!ip.isNullOrBlank()) putExtra("car_ip", ip)
        }
        startService(intent)
    }

    companion object {
        private const val PREFS_NAME = "dilinkauto_onboarding"
        private const val KEY_ONBOARDING_DONE = "has_completed_onboarding"
    }
}
