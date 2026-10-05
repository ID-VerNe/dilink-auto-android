package com.dilinkauto.client.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.protocol.AppPrefs
import com.dilinkauto.protocol.ImeRestore
import com.dilinkauto.protocol.VdDeploy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restores the phone's physical display and IME state after the VD server
 * tears down.
 *
 * The VD server powers off the physical panel directly via
 * `DisplayControl.setDisplayPowerMode(0)` — a deeper off than PowerManager
 * can recover from. Its own cleanup() only runs if the process exits cleanly
 * (CMD_STOP received, or SIGTERM so the JVM shutdown hook fires). When the
 * lifecycle channel breaks so CMD_STOP never arrives, or the process hangs in
 * a native futex, cleanup() never runs and the panel stays off → phone is
 * unusable. PowerManager wakeUp/wake-locks cannot reverse this; only
 * `setDisplayPowerMode(2)` / `cmd display power-on` can, which needs shell
 * privileges (Shizuku).
 *
 * Layers (tried in order, each independent):
 *  1. Shizuku: stop VD server + `cmd display power-on` + IME restore.
 *  2. PowerManager.wakeUp() via reflection — system-level wake.
 *  3. Launch MainActivity with FLAG_TURN_SCREEN_ON — WindowManager triggers display on.
 *  4. WakeLock with ACQUIRE_CAUSES_WAKEUP — framework-level.
 *
 * ── Why this class owns its own CoroutineScope ──
 * The previous version ran on the Service's `serviceScope`, which
 * `ConnectionService.onDestroy()` cancels. When the user hit "stop" (or the
 * system reclaimed the Service) the restore coroutine was cancelled *between*
 * the `pkill` and the `cmd display power-on`, leaving the physical panel off
 * with nothing left to turn it back on:
 *
 *     Force-waking physical display          x3
 *     Shizuku display restore failed: Job was cancelled  x3
 *
 * It also had no single-flight guard, so one disconnect could fire it 9 times
 * (7 `cleanupSession()` call sites, two of them on network callbacks), each one
 * racing a `pkill` against the previous restore. [restoreScope] is therefore a
 * process-lifetime scope that no Service lifecycle can cancel, and [inFlight]
 * collapses concurrent requests into one.
 *
 * @param savedIme the IME id cached at handshake time, or null to fall back to
 *                 the persisted `saved_default_ime` pref. The caller clears its
 *                 in-memory copy after invoking [restore] since the value is
 *                 captured here.
 */
internal class PhoneDisplayRestorer(
    private val appContext: Context
) {
    /**
     * Process-lifetime scope. Deliberately NOT the Service's scope: the whole
     * point of this class is that it must finish even when the Service is dying.
     */
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Single-flight guard: collapse concurrent restore requests into one run. */
    private val inFlight = AtomicBoolean(false)

    @android.annotation.SuppressLint("BlockedPrivateApi")
    fun restore(savedIme: String?) {
        if (!inFlight.compareAndSet(false, true)) {
            FileLog.d(TAG, "Restore already in flight — skipping duplicate request")
            return
        }
        restoreScope.launch {
            // NonCancellable: once we start killing the engine we must run the
            // power-on too. A cancellation in the middle leaves the panel off.
            withContext(NonCancellable) {
                try {
                    restoreInternal(savedIme)
                } finally {
                    inFlight.set(false)
                }
            }
        }
    }

    private suspend fun restoreInternal(savedIme: String?) {
        try {
            FileLog.i(TAG, "Force-waking physical display")

            // Layer 0: Restore SurfaceFlinger-level display power via Shizuku.
            if (ShizukuManager.isAvailable) {
                try {
                    // Two-stage stop in ONE shell line (SIGTERM -> 1s -> SIGKILL).
                    // SIGTERM lets the JVM run PipelineServer's shutdown hook, so
                    // cleanup() releases the VirtualDisplay and restores IME /
                    // letterbox / screen settings. The old `pkill -9` skipped the
                    // hook outright and leaked a VD on every reconnect.
                    ShizukuManager.execAndWait(VdDeploy.stopCommand)
                    ShizukuManager.execAndWait("cmd display power-on 0 2>/dev/null")
                    val targetIme = savedIme
                        ?: appContext.getSharedPreferences(AppPrefs.FILE_NAME, Context.MODE_PRIVATE)
                            .getString(AppPrefs.SAVED_DEFAULT_IME, null)
                    if (ImeRestore.shouldRestoreIme(targetIme)) {
                        ShizukuManager.execAndWait(ImeRestore.imeRestoreCommandLine(targetIme!!))
                        FileLog.i(TAG, "Original IME restored via Shizuku: $targetIme")
                    }
                    FileLog.i(TAG, "Physical display restored via Shizuku (stop + power-on + IME)")
                } catch (e: Exception) {
                    FileLog.w(TAG, "Shizuku display restore failed: ${e.message}")
                }
            } else {
                FileLog.w(TAG, "Shizuku unavailable — relying on framework wake layers only")
            }

            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

            // Layer 1: PowerManager.wakeUp() — direct system call
            try {
                val wakeUp = PowerManager::class.java.getDeclaredMethod(
                    "wakeUp", Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, String::class.java
                )
                wakeUp.invoke(pm, SystemClock.uptimeMillis(),
                    5 /* WAKE_REASON_APPLICATION */, "DiLink:restore")
                FileLog.i(TAG, "Display wakeUp() succeeded")
            } catch (e: Exception) {
                FileLog.d(TAG, "wakeUp() not available: ${e.message}")
            }

            // Layer 2: Launch MainActivity with FLAG_TURN_SCREEN_ON.
            try {
                val intent = Intent(appContext, Class.forName("com.dilinkauto.client.MainActivity"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.addFlags(0x10000000) // FLAG_TURN_SCREEN_ON
                appContext.startActivity(intent)
                FileLog.i(TAG, "Launched MainActivity with FLAG_TURN_SCREEN_ON")
            } catch (e: Exception) {
                FileLog.d(TAG, "Activity launch for wake failed: ${e.message}")
            }

            // Layer 3: WakeLock with ACQUIRE_CAUSES_WAKEUP
            @Suppress("DEPRECATION")
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE
            val wl = pm.newWakeLock(flags, "DiLink:display:restore2")
            wl.acquire(3000)
            wl.release()
        } catch (e: Exception) {
            FileLog.w(TAG, "forceWakeScreen error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PhoneDisplayRestorer"
    }
}