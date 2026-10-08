package com.dilinkauto.client.service

import android.content.Context
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.protocol.Ports
import java.io.File

/**
 * Drives one car-app install: locate the car, connect over ADB, skip if already
 * current, otherwise push and install.
 *
 * Extracted from [ConnectionService.installCarApp]
 * (docs/audit-srp-dry.md SRP-2). That method was ~70 lines of sequencing that
 * belonged to no single connection concern — it neither opens nor tears down the
 * streaming connection, it just runs alongside it and reports progress.
 *
 * Status is reported through a callback rather than a `StateFlow` so this class
 * has no opinion about how the UI observes it; the service keeps owning
 * `_installStatus`.
 */
internal class CarInstallCoordinator(
    private val context: Context,
    private val apkFile: File,
    private val installer: CarAppInstaller,
    /** Supplies the streaming connection's remote IP, or null when not connected. */
    private val connectedCarIp: () -> String?,
    /** Clears the car's icon cache after a reinstall. */
    private val onReinstalled: () -> Unit,
    private val status: (String) -> Unit
) {

    /**
     * Runs the install flow off the caller's thread.
     *
     * @param explicitIp a manually entered IP to try first; null means search.
     */
    suspend fun install(explicitIp: String? = null) {
        // Keep the final status on screen when the failure needs user action
        // (e.g. waiting for ADB authorization), instead of clearing it.
        var keepStatus = false
        try {
            if (!apkFile.exists()) {
                status(context.getString(R.string.car_install_status_car_apk_not_found))
                FileLog.w(TAG, "No embedded car APK")
                return
            }

            status(
                if (explicitIp != null) context.getString(R.string.car_install_status_connecting_to, explicitIp)
                else context.getString(R.string.car_install_status_searching)
            )
            val carIp = resolveCarIp(explicitIp) ?: run {
                status(context.getString(R.string.car_install_status_car_not_found))
                FileLog.w(TAG, "Could not find car ADB on USB or network")
                return
            }

            status(context.getString(R.string.car_install_status_connecting_to, carIp))
            FileLog.i(TAG, "Connecting to car ADB at $carIp:${Ports.ADB_PORT}")

            val dadb = installer.connect(carIp)
            if (dadb == null) {
                // Needs the user to approve the ADB prompt on the car; hold the
                // message instead of clearing it after the delay below.
                status(context.getString(R.string.car_install_status_auth_needed))
                keepStatus = true
                return
            }
            FileLog.d(TAG, "Dadb.create() succeeded")

            try {
                status(context.getString(R.string.car_install_status_checking_version))
                val installedVersionName = installer.readInstalledVersion(dadb)
                val myVersionName = AppVersion.label(context)
                FileLog.i(TAG, "Car app: installed=$installedVersionName, embedded=$myVersionName")

                if (compareVersions(myVersionName, installedVersionName) <= 0) {
                    status(context.getString(R.string.car_install_status_already_up_to_date, installedVersionName))
                    return
                }

                val result = installer.pushAndInstall(dadb, apkFile, myVersionName)
                FileLog.i(TAG, "Install result: ${result.trim()}")

                if (result.contains("Success")) {
                    onReinstalled()
                    status(context.getString(R.string.car_install_status_car_installed, myVersionName))
                } else {
                    status(context.getString(R.string.car_install_status_failed, result.trim()))
                }
            } finally {
                dadb.close()
            }
        } catch (e: Exception) {
            status(context.getString(R.string.car_install_status_error, e.message ?: "unknown"))
            FileLog.e(TAG, "Car app install failed", e)
        } finally {
            if (!keepStatus) {
                kotlinx.coroutines.delay(5000)
                status("")
            }
        }
    }

    /** @return the car IP to install against, or null when none is reachable. */
    private suspend fun resolveCarIp(explicitIp: String?): String? {
        if (!explicitIp.isNullOrBlank()) {
            if (CarIpLocator.probePortSync(explicitIp, Ports.ADB_PORT)) return explicitIp
            status(context.getString(R.string.car_install_status_not_reachable, explicitIp))
            return null
        }
        return CarIpLocator.findCarAdb(connectedCarIp())
    }

    companion object {
        private const val TAG = "CarInstall"
    }
}