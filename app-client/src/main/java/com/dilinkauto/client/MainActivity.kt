package com.dilinkauto.client

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dilinkauto.client.service.ConnectionService
import com.dilinkauto.client.service.UpdateManager
import com.dilinkauto.client.ui.DiLinkAutoTheme
import com.dilinkauto.client.ui.MainScreen
import com.dilinkauto.client.ui.OnboardingScreen
import com.dilinkauto.client.ui.SettingsScreen
import kotlinx.coroutines.launch
class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) { prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply() }

    private var showOnboarding = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevent screen lock while streaming — HyperOS may override system timeout settings
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        showOnboarding.value = !onboardingCompleted

        // Auto-start the service when the app is opened (e.g. by the car via USB ADB).
        // Only if onboarding is done and the service isn't already running — calling
        // startForegroundService on an already-running service is harmless but noisy.
        if (onboardingCompleted && ConnectionService.serviceState.value == ConnectionService.State.IDLE) {
            startConnectionService()
        }

        // Check for updates every time the app is opened
        if (onboardingCompleted) {
            UpdateManager.checkForUpdate(force = true)
        }

        setContent {
            DiLinkAutoTheme {
                val installStatus by ConnectionService.installStatusFlow.collectAsState()
                if (showOnboarding.value) {
                    OnboardingScreen(
                        onComplete = {
                            onboardingCompleted = true
                            showOnboarding.value = false
                        },
                        onInstallOnCar = { installOnCar(null) },
                        installStatus = installStatus
                    )
                } else {
                    var showSettings by remember { mutableStateOf(false) }
                    var showAllowlist by remember { mutableStateOf(false) }
                    if (showAllowlist) {
                        AllowlistScreen(onBack = { showAllowlist = false })
                    } else if (showSettings) {
                        SettingsScreen(
                            onBack = { showSettings = false },
                            onOpenAllFilesAccess = { openAllFilesAccess() },
                            onOpenBatteryExemption = { openBatteryExemption() },
                            onOpenAccessibility = { openAccessibilitySettings() },
                            onOpenDeveloperOptions = { openDeveloperOptions() },
                            onOpenAllowlist = { showAllowlist = true },
                            onCheckForUpdate = { UpdateManager.checkForUpdate(force = true) },
                            onDownloadUpdate = { UpdateManager.downloadUpdate() },
                            onInstallUpdate = { UpdateManager.installUpdate(this) }
                        )
                    } else {
                        MainScreen(
                            onStartService = { startConnectionService() },
                            onStopService = { stopConnectionService() },
                            onInstallOnCar = { ip -> installOnCar(ip) },
                            onOpenSettings = { showSettings = true },
                            onShareLogs = { shareLogs() },
                            onDownloadUpdate = { UpdateManager.downloadUpdate() },
                            onInstallUpdate = { UpdateManager.installUpdate(this) }
                        )
                    }
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

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun installOnCar(ip: String? = null) {
        val intent = Intent(this, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_INSTALL_CAR
            if (!ip.isNullOrBlank()) putExtra("car_ip", ip)
        }
        startService(intent)
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun openBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Battery exemption request failed: ${e.message}")
        }
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"))
            } catch (_: Exception) {}
        }
    }

    private fun shareLogs() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val zipFile = FileLog.zipLogs()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (zipFile != null) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        zipFile
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.share_logs_title)))
                } else {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.share_logs_no_logs),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "dilinkauto_onboarding"
        private const val KEY_ONBOARDING_DONE = "has_completed_onboarding"
    }
}
