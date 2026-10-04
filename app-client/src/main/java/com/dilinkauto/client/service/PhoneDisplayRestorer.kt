package com.dilinkauto.client.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.dilinkauto.client.FileLog
import com.dilinkauto.client.ShizukuManager
import com.dilinkauto.protocol.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Restores the phone's physical display and IME state after the VD server
 * tears down.
 *
 * The VD server powers off the physical panel directly via
 * `DisplayControl.setDisplayPowerMode(0)` — a deeper off than PowerManager
 * can recover from. Its own cleanup() only runs if the process exits cleanly
 * (CMD_STOP received). When the lifecycle channel breaks so CMD_STOP never
 * arrives, or the process hangs in a native futex, cleanup() never runs and
 * the panel stays off → phone is unusable. PowerManager wakeUp/wake-locks
 * cannot reverse this; only `setDisplayPowerMode(2)` / `cmd display power-on`
 * can, which needs shell privileges (Shizuku). This class kills the VD server
 * first so it stops re-powering-off the panel during touch injection, then
 * restores the panel and the original IME.
 *
 * Layers (tried in order, each independent):
 *  1. Shizuku: pkill PipelineServer + `cmd display power-on` + IME restore.
 *  2. PowerManager.wakeUp() via reflection — system-level wake.
 *  3. Launch MainActivity with FLAG_TURN_SCREEN_ON — WindowManager triggers display on.
 *  4. WakeLock with ACQUIRE_CAUSES_WAKEUP — framework-level.
 *
 * @param savedIme the IME id cached at handshake time, or null to fall back to
 *                 the persisted `saved_default_ime` pref. The caller clears its
 *                 in-memory copy after invoking [restore] since the value is
 *                 captured here.
 */
internal class PhoneDisplayRestorer(
    private val appContext: Context,
    private val scope: CoroutineScope
) {
    @android.annotation.SuppressLint("BlockedPrivateApi")
    fun restore(savedIme: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                FileLog.i(TAG, "Force-waking physical display")

                // Layer 0: Restore SurfaceFlinger-level display power via Shizuku.
                if (ShizukuManager.isAvailable) {
                    try {
                        ShizukuManager.execAndWait(com.dilinkauto.protocol.VdDeploy.killCommandForce)
                        delay(150)
                        ShizukuManager.execAndWait("cmd display power-on 0 2>/dev/null")
                        val targetIme = savedIme
                            ?: appContext.getSharedPreferences(AppPrefs.FILE_NAME, Context.MODE_PRIVATE)
                                .getString(AppPrefs.SAVED_DEFAULT_IME, null)
                        if (com.dilinkauto.protocol.ImeRestore.shouldRestoreIme(targetIme)) {
                            ShizukuManager.execAndWait(com.dilinkauto.protocol.ImeRestore.imeRestoreCommandLine(targetIme!!))
                            FileLog.i(TAG, "Original IME restored via Shizuku: $targetIme")
                        }
                        FileLog.i(TAG, "Physical display restored via Shizuku (pkill + power-on + IME)")
                    } catch (e: Exception) {
                        FileLog.w(TAG, "Shizuku display restore failed: ${e.message}")
                    }
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
    }

    companion object {
        private const val TAG = "PhoneDisplayRestorer"
    }
}
