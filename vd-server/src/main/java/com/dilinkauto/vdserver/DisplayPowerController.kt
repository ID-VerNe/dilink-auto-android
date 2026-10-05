package com.dilinkauto.vdserver

import java.io.OutputStream

/**
 * Powers the phone's physical display on/off and configures the IME policy on
 * the VirtualDisplay, via reflection into the framework's DisplayControl class
 * (shell-only; the VD server runs as shell via app_process).
 *
 * Also persists and restores the original IME so the VD's linkpc IME doesn't
 * outlive the session. The IME-restore path is used by [PipelineServer.cleanup].
 *
 * Extracted from [PipelineServer] to keep display/IME framework reflection
 * out of the pipeline body.
 */
internal class DisplayPowerController(
    private val shellProvider: () -> OutputStream?,
    private val execShellOutput: (String) -> String?,
    private val logErr: (String) -> Unit
) {
    private var displayControlClass: Class<*>? = null
    private var displayControlLoaded = false

    var savedDefaultIme: String? = null
        private set

    private var savedScreenOffTimeout: String? = null
    private var savedLiftWakeup: String? = null
    private var savedProximityWakeup: String? = null

    /** Persist the original IME and screen settings so they can be restored on teardown. */
    fun saveCurrentIme() {
        try {
            val cur = execShellOutput("settings get secure default_input_method")?.trim()
            if (com.dilinkauto.protocol.ImeRestore.shouldRestoreIme(cur)) {
                savedDefaultIme = cur
                log("Saved original IME: $savedDefaultIme")
            }
        } catch (_: Exception) {}
        try {
            savedScreenOffTimeout = execShellOutput("settings get system screen_off_timeout")?.trim() ?: "60000"
            savedLiftWakeup = execShellOutput("settings get system lift_wakeup_enabled")?.trim() ?: "1"
            savedProximityWakeup = execShellOutput("settings get system proximity_wakeup_enabled")?.trim() ?: "1"
        } catch (_: Exception) {}
    }

    /** Set the IME policy on display [id] to fall back to the physical IME (0). */
    fun setDisplayImePolicy(id: Int) {
        try { val wm = Class.forName("android.view.IWindowManager\$Stub").getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java).invoke(null, "window")); wm.javaClass.getDeclaredMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, id, 0) } catch (_: Exception) {}
    }

    /**
     * Power the physical panel on ([on]=true) or off ([on]=false). Uses
     * DisplayControl reflection; falls back to `cmd display power-on/off`
     * (API 29+) on failure. On API < 29 the shell fallback is a no-op and
     * the panel cannot be restored here — surfaced via [logErr].
     */
    fun setPhysicalDisplayPower(on: Boolean) {
        try {
            if (!displayControlLoaded) { try { displayControlClass = Class.forName("com.android.server.display.DisplayControl"); displayControlLoaded = true } catch (_: Exception) { try { val clf = Class.forName("dalvik.system.DelegateLastClassLoader").getDeclaredConstructor(String::class.java, String::class.java, ClassLoader::class.java); clf.isAccessible = true; displayControlClass = (clf.newInstance("/system/framework/services.jar", null, ClassLoader.getSystemClassLoader()) as ClassLoader).loadClass("com.android.server.display.DisplayControl"); displayControlLoaded = true } catch (_: Exception) { logErr("DisplayControl load failed") } } }
            val cls = displayControlClass; if (cls != null) { val gid = cls.getDeclaredMethod("getPhysicalDisplayIds").apply { isAccessible = true }; val ids = gid.invoke(null) as LongArray; val sp = cls.getDeclaredMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }; for (id in ids) { sp.invoke(null, cls.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(null, id), if (on) 2 else 0) } }
        } catch (_: Exception) {
            // Shell fallback `cmd display power-on/off` was added in API 29; on API 26-28 it
            // is a no-op (subcommand absent) and the panel will not be restored if the
            // DisplayControl reflection above also fails. Surface this so the failure is
            // observable rather than silent.
            if (android.os.Build.VERSION.SDK_INT < 29) logErr("DisplayControl reflection failed on API < 29; shell fallback (cmd display power) is API 29+ and will NOT restore the panel")
            try { execShell("cmd display power-${if (on) "on" else "off"} 0") } catch (_: Exception) {}
        }
    }

    /**
     * Restore the previously-saved IME and screen settings.
     *
     * Runs unconditionally on teardown (the caller re-powers the panel
     * separately) and is idempotent. Each setting is restored from the snapshot
     * taken in [saveCurrentIme]. The old code skipped `screen_off_timeout`
     * whenever the snapshot equalled `2147483647` — but because the snapshot was
     * taken *after* [VirtualDisplayCreator] had already written that sentinel,
     * the skip fired on every session and the user's real timeout was lost
     * forever. Validity, not equality-with-our-own-sentinel, is the right test.
     */
    fun restoreIme() {
        val ime = savedDefaultIme
        if (ime != null && com.dilinkauto.protocol.ImeRestore.shouldRestoreIme(ime)) {
            try {
                com.dilinkauto.protocol.ImeRestore.imeRestoreCommands(ime).forEach { execShell(it) }
                log("Restored original IME: $ime")
            } catch (_: Exception) {}
        }
        restoreSetting("screen_off_timeout", savedScreenOffTimeout)
        restoreSetting("lift_wakeup_enabled", savedLiftWakeup)
        restoreSetting("proximity_wakeup_enabled", savedProximityWakeup)
    }

    /**
     * Write [value] back to `system.[key]` when it is a plausible snapshot.
     *
     * "Plausible" = non-blank and not a `settings get` miss marker (`null` /
     * `undefined`), and numeric keys must hold a positive number. A failed
     * snapshot yields one of those markers; writing them back would leave the
     * device in a worse state than leaving the current value alone.
     */
    private fun restoreSetting(key: String, value: String?) {
        val v = value?.trim()
        if (v.isNullOrEmpty() || v.equals("null", true) || v.equals("undefined", true)) return
        val numeric = v.toLongOrNull()
        if (numeric != null && numeric <= 0L) return
        try { execShell("settings put system $key $v") } catch (_: Exception) {}
    }

    private fun log(msg: String) = PipeLog.log(msg)
    private fun execShell(cmd: String) = ShellExec.execShell(shellProvider(), cmd)
}
