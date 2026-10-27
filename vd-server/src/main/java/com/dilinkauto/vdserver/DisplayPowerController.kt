package com.dilinkauto.vdserver

import com.dilinkauto.protocol.ShellVerdict
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
    private val logErr: (String) -> Unit,
    /** `screen_off_timeout` 原值存档（跨进程；见 [TimeoutSnapshotFile]，2026-10-17 S19 实证新增）。 */
    private val snapshotFile: TimeoutSnapshotFile = TimeoutSnapshotFile(),
) {
    private var displayControlClass: Class<*>? = null
    private var displayControlLoaded = false

    /** `cmd display` 是否支持 `power-reset`：null = 未探测；见 [powerResetSupported]。 */
    private var powerResetSupportedCached: Boolean? = null

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
                PipeLog.log("Saved original IME: $savedDefaultIme")
            }
        } catch (_: Exception) {}
        try {
            // 2026-10-17 S19 实证：读到哨兵（上个会话被 kill）时先用文件存档修正，再落盘本次原值——
            // 纯内存快照在 SIGKILL 路径会丢，导致用户原值被 fallback 60000 永久覆盖（vd-server.log 行 148→205）。
            val cur = execShellOutput("settings get system screen_off_timeout")?.trim()
            val effective = SessionScreenTimeout.effectiveSnapshot(cur, snapshotFile.read())
            savedScreenOffTimeout = effective
            snapshotFile.write(effective)
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
     * DisplayControl reflection; falls back to `cmd display power-reset/off`
     * (API 29+) when the reflection path does not fully succeed. On API < 29 the
     * shell fallback is a no-op and the panel cannot be restored here — surfaced
     * via [logErr].
     *
     * 2026-10-27（issue #23）：
     *  - 反射路径改为返回布尔，**类加载失败也算失败**并进入 shell 兜底。旧实现
     *    在 `DisplayControl` 加载失败时 `displayControlClass` 为 null 后直接返回，
     *    shell 兜底根本不会执行 —— 这是「两层都死」的另一条路径。
     *  - `power-reset` 不再 fire-and-forget：命令带退出码标记，按
     *    [ShellVerdict] 判定，失败打 E 并带原始输出（Flyme 现场是
     *    `Unknown command: power-reset`，旧实现完全看不见）。
     *  - 该 ROM 是否支持 `power-reset` 用 `cmd display help` 探测一次并缓存；
     *    探测失败（拿不到 help）时仍然尝试，避免把可用 ROM 误判为不支持。
     */
    fun setPhysicalDisplayPower(on: Boolean) {
        if (applyDisplayControlPower(on)) return
        if (on) shellPowerOn() else shellPowerOff()
    }

    /** 反射路径：完全成功返回 true；任何一步失败（含类加载）返回 false。 */
    private fun applyDisplayControlPower(on: Boolean): Boolean {
        try {
            if (!displayControlLoaded) { try { displayControlClass = Class.forName("com.android.server.display.DisplayControl"); displayControlLoaded = true } catch (_: Exception) { try { val clf = Class.forName("dalvik.system.DelegateLastClassLoader").getDeclaredConstructor(String::class.java, String::class.java, ClassLoader::class.java); clf.isAccessible = true; displayControlClass = (clf.newInstance("/system/framework/services.jar", null, ClassLoader.getSystemClassLoader()) as ClassLoader).loadClass("com.android.server.display.DisplayControl"); displayControlLoaded = true } catch (_: Exception) { logErr("DisplayControl load failed") } } }
        } catch (_: Exception) {
            logErr("DisplayControl load failed: unexpected error")
        }
        val cls = displayControlClass ?: return false
        return try {
            val gid = cls.getDeclaredMethod("getPhysicalDisplayIds").apply { isAccessible = true }
            val ids = gid.invoke(null) as LongArray
            if (ids.isEmpty()) {
                logErr("DisplayControl returned no physical displays")
                return false
            }
            val sp = cls.getDeclaredMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            for (id in ids) {
                sp.invoke(
                    null,
                    cls.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(null, id),
                    if (on) 2 else 0
                )
            }
            true
        } catch (e: Exception) {
            logErr("DisplayControl setDisplayPowerMode failed: ${e.message}")
            false
        }
    }

    /**
     * Shell 兜底点亮：`cmd display power-reset ID`（API 29+）。
     *
     * 2026-10-10 实机修正：这里原来写的是 `power-on` —— Android 15 实机
     * `cmd display help` 证明该子命令**不存在**（只有 power-off 和 power-reset
     * "Turn the display power to a state the display supposed to have"）。
     * 2026-10-27 Flyme 现场（issue #23）：连 `power-reset` 也不存在，
     * 返回 `Unknown command: power-reset`。
     */
    private fun shellPowerOn() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            logErr("DisplayControl reflection failed on API < 29; shell fallback (cmd display power) is API 29+ and will NOT restore the panel")
        }
        if (!powerResetSupported()) {
            logErr("cmd display power-reset is not supported on this ROM — physical panel may stay off")
            return
        }
        val out = execShellOutput(ShellVerdict.wrap(POWER_RESET_COMMAND))
        if (ShellVerdict.succeeded(out)) {
            PipeLog.log("Physical display restored via power-reset ($POWER_RESET_COMMAND)")
        } else {
            logErr("power-reset did not take effect: ${ShellVerdict.detail(out)}")
        }
    }

    /** Shell 兜底熄屏：`cmd display power-off 0`（fire-and-forget，失败不影响清理流程）。 */
    private fun shellPowerOff() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            logErr("DisplayControl reflection failed on API < 29; shell fallback (cmd display power) is API 29+ and will NOT power the panel off")
        }
        try { execShell("cmd display power-off 0") } catch (_: Exception) {}
    }

    /**
     * 该 ROM 的 `cmd display` 是否支持 `power-reset`，探测一次并缓存。
     *
     * `null` = 还没探测过；探测拿不到 help 输出时返回 true 且不缓存（仍然尝试，
     * 免得把能用的 ROM 误跳过）。
     */
    private fun powerResetSupported(): Boolean {
        powerResetSupportedCached?.let { return it }
        val help = execShellOutput("cmd display help")?.trim().orEmpty()
        if (help.isEmpty()) return true
        val supported = help.contains("power-reset")
        powerResetSupportedCached = supported
        if (!supported) logErr("cmd display help has no power-reset on this ROM — skipping shell fallback")
        return supported
    }

    /**
     * Restore the previously-saved IME and screen settings.
     *
     * Runs unconditionally on teardown (the caller re-powers the panel
     * separately) and is idempotent. Each setting is restored from the snapshot
     * taken in [saveCurrentIme].
     *
     * S-M10: `screen_off_timeout` is special. When the previous session was killed
     * abnormally (SIGKILL / `Runtime.halt(1)` — both reachable, see the watchdog and
     * the car's teardown), the setting still holds the sentinel we wrote, so the
     * snapshot is *our own sentinel* rather than the user's value. Restoring it would
     * keep the phone awake forever and be inherited by every later session. The
     * sentinel is therefore treated as "no valid snapshot" and replaced with the
     * documented default (60s) instead — see [SessionScreenTimeout].
     *
     * 2026-10-17（S19 真机实证）起：原值另有文件存档 [snapshotFile]，恢复时**优先**
     * 读它——kill 路径（快照仅存内存会丢）下个会话也能恢复真实原值而非 60s。
     * 存档缺失/损坏时退化为上述 S-M10 语义（哨兵 → 默认值）。
     */
    fun restoreIme() {
        val ime = savedDefaultIme
        if (ime != null && com.dilinkauto.protocol.ImeRestore.shouldRestoreIme(ime)) {
            try {
                com.dilinkauto.protocol.ImeRestore.imeRestoreCommands(ime).forEach { execShell(it) }
                PipeLog.log("Restored original IME: $ime")
            } catch (_: Exception) {}
        }
        val timeout = snapshotFile.read() ?: savedScreenOffTimeout
        restoreSetting("screen_off_timeout", SessionScreenTimeout.valueToRestore(timeout))
        if (SessionScreenTimeout.isSentinel(timeout)) {
            PipeLog.log("screen_off_timeout snapshot was our own sentinel (previous session was killed) — falling back to ${SessionScreenTimeout.DEFAULT_TIMEOUT_MS}ms")
        }
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

    private fun execShell(cmd: String) = ShellExec.execShell(shellProvider(), cmd)

    companion object {
        /** 点亮物理屏的子命令；退出码由 [ShellVerdict] 判定。 */
        const val POWER_RESET_COMMAND = "cmd display power-reset 0"
    }
}
