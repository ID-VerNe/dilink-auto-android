package com.dilinkauto.client.service

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.ShizukuManager
import dadb.Dadb
import java.io.File

/**
 * APK install helpers extracted from [UpdateManager].
 *
 * Each function is a pure file/IPC operation that reports outcome via return
 * value; [UpdateManager] owns the state machine and translates these outcomes
 * into [UpdateState] transitions. This keeps the install mechanics testable
 * independent of the update fetch/download lifecycle.
 */

/** Outcome of an install attempt that doesn't go through the system installer. */
internal sealed class InstallOutcome {
    /** Install succeeded. */
    data object Success : InstallOutcome()
    /** ADB self-install failed; caller should fall back to the system installer. */
    data object NeedsSystemInstaller : InstallOutcome()
}

/**
 * Stage [apkFile] to [tmpPath] (default `/data/local/tmp/update.apk`) for a
 * `pm install` via Shizuku. Tries a direct file copy first (works when the
 * app has FS access to the path), falls back to Shizuku's `copyToFile` for
 * paths only shell can reach.
 */
internal fun stageApkForShizuku(apkFile: File, tmpPath: String = "/data/local/tmp/update.apk"): Boolean {
    val tmpFile = File(tmpPath)
    val directCopy = try {
        apkFile.copyTo(tmpFile, overwrite = true)
        tmpFile.setReadable(true, false)
        true
    } catch (_: Exception) {
        false
    }
    if (directCopy) return true
    return ShizukuManager.copyToFile(apkFile, tmpPath)
}

/**
 * Install [apkFile] by pushing it over a self-hosted TCP ADB connection
 * (127.0.0.1:5555) and running `pm install`. Used as a fallback when Shizuku's
 * `pm install` fails. Returns [InstallOutcome.NeedsSystemInstaller] if ADB
 * self-connect or the install command fails, so the caller can fall back to
 * the system package installer.
 */
internal suspend fun tryDadbInstall(appContext: Context, apkFile: File, version: String): InstallOutcome {
    val tag = "UpdateManager"
    return try {
        val keyPair = AdbKeyUtil.ensureAdbKeyPair(appContext.filesDir)

        val dadb: Dadb? = AdbKeyUtil.dadbCreateWithTimeout(
            tag, "127.0.0.1", com.dilinkauto.protocol.Discovery.ADB_PORT, keyPair, 10L
        )

        if (dadb == null) {
            FileLog.w(tag, "Dadb self-connect failed, falling back to system installer")
            return InstallOutcome.NeedsSystemInstaller
        }

        try {
            FileLog.i(tag, "Dadb self-install ($version): pushing APK...")
            val remotePath = "/data/local/tmp/update.apk"
            dadb.push(apkFile, remotePath)
            val result = dadb.shell("pm install -r $remotePath").allOutput
            FileLog.i(tag, "Dadb self-install result: ${result.trim()}")
            if (result.contains("Success")) {
                FileLog.i(tag, "Dadb self-install succeeded")
                InstallOutcome.Success
            } else {
                FileLog.w(tag, "Dadb self-install failed: ${result.trim()}, falling back to system installer")
                InstallOutcome.NeedsSystemInstaller
            }
        } finally {
            dadb.close()
        }
    } catch (e: Exception) {
        FileLog.e(tag, "Dadb self-install error, falling back to system installer", e)
        InstallOutcome.NeedsSystemInstaller
    }
}

/**
 * Launch the system package installer for [apkFile] via a `content://` URI.
 * Returns null on success, or an error message string if the launch failed
 * (caller surfaces it as an [UpdateState.Error]).
 */
internal fun launchSystemInstaller(context: Context, apkFile: File): String? {
    return try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        null
    } catch (e: Exception) {
        FileLog.e("UpdateManager", "System installer also failed", e)
        e.message ?: "unknown"
    }
}
