package com.dilinkauto.client.service

import android.content.Context
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.R
import com.dilinkauto.protocol.Discovery
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Installs the embedded `app-server.apk` onto the car via ADB-over-WiFi (dadb),
 * with a hard 15-second connect timeout.
 *
 * Extracted from [ConnectionService] (Phase K3 / code-audit 5.3). The install
 * logic was previously duplicated between `autoUpdateCarApp` and `installCarApp`;
 * a fix in one path had to be hand-mirrored to the other.
 *
 * What lives here: key-pair generation, `Dadb.create()` with the
 * `Future.get(15, SECONDS)` timeout that coroutine cancellation cannot
 * interrupt, the `pm install -r` push, and the `am start` restart.
 *
 * What does NOT live here: the `_installStatusStatic` observable (the caller
 * owns it and receives status via [onStatus]) and the car-IP lookup
 * (see [CarIpLocator]).
 *
 * @param context service context — used for `filesDir` (key storage)
 * @param onStatus invoked with user-facing status strings; caller routes them
 *   to `_installStatusStatic`
 */
class CarAppInstaller(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {

    private val privKey: File = File(context.filesDir, "adbkey")
    private val pubKey: File = File(context.filesDir, "adbkey.pub")

    /**
     * Connects to the car at [carIp]:5555 with the 15s timeout and returns an
     * open [Dadb]. Caller owns [Dadb.close]. Returns null on timeout — the car
     * auth dialog is likely pending.
     *
     * `Dadb.create()` does blocking socket I/O that coroutine cancellation
     * cannot interrupt, so the timeout is enforced via `Future.get(15, SECONDS)`
     * on a dedicated single-thread executor (always shut down).
     */
    fun connect(carIp: String): Dadb? {
        val keyPair = ensureKeyPair()
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<Dadb> {
                Dadb.create(carIp, CAR_ADB_PORT, keyPair)
            }
            try {
                future.get(DADB_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                FileLog.w(TAG, "Dadb.create() timed out after ${DADB_TIMEOUT_SECONDS}s — likely waiting for car auth dialog")
                null
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Reads the car's currently-installed `app-server` version name, or "0"
     * if not installed. Requires an open [dadb] session.
     */
    fun readInstalledVersion(dadb: Dadb): String {
        val output = dadb.shell(
            "dumpsys package com.dilinkauto.server 2>/dev/null | grep versionName"
        ).allOutput
        return Regex("""versionName=(\S+)""").find(output)?.groupValues?.get(1) ?: "0"
    }

    /**
     * Pushes [apkFile] to `/data/local/tmp/app-server.apk`, runs `pm install -r`,
     * and on success restarts the car app. Returns the `pm install` shell output
     * (contains "Success" on a successful install). The caller owns [dadb]
     * lifecycle (close it after).
     */
    fun pushAndInstall(dadb: Dadb, apkFile: File, versionLabel: String): String {
        val remotePath = "/data/local/tmp/app-server.apk"
        onStatus.invoke(context.getString(R.string.car_install_status_pushing_apk, apkFile.length() / 1024 / 1024))
        dadb.push(apkFile, remotePath)
        FileLog.i(TAG, "Car APK pushed (${apkFile.length()} bytes)")

        onStatus.invoke(context.getString(R.string.car_install_status_installing_version, versionLabel))
        val result = dadb.shell("pm install -r $remotePath").allOutput
        FileLog.i(TAG, "Install result: ${result.trim()}")

        if (result.contains("Success")) {
            onStatus.invoke(context.getString(R.string.car_install_status_launching_car_app))
            dadb.shell("am start --activity-clear-task -n ${com.dilinkauto.protocol.AppTargets.CAR_MAIN_ACTIVITY}")
        }
        return result
    }

    private fun ensureKeyPair(): AdbKeyPair {
        if (!privKey.exists()) {
            context.filesDir.mkdirs()
            AdbKeyPair.generate(privKey, pubKey)
        }
        return AdbKeyPair.read(privKey, pubKey)
    }

    companion object {
        private const val TAG = "CarAppInstaller"
        private const val CAR_ADB_PORT = Discovery.ADB_PORT
        private const val DADB_TIMEOUT_SECONDS = 15L
    }
}
