package com.dilinkauto.client.service

import com.dilinkauto.protocol.ImeRestore
import com.dilinkauto.protocol.ShellVerdict

/**
 * 一条设备侧 shell 命令的可见结果。
 *
 * 与 `com.dilinkauto.client.ShizukuManager.ExecResult` 同构，但刻意不复用：
 * 那个类挂在持有 Shizuku binder 的 object 上，单测只关心文本，不需要把
 * Android/Shizuku 的类拖进来。
 */
internal data class ShellOutcome(val stdout: String, val stderr: String)

/** 恢复序列的日志接缝：生产实现走 `FileLog`，单测用内存记录。 */
internal interface RestoreLog {
    fun i(msg: String)
    fun w(msg: String)
    fun d(msg: String)
}

/**
 * 一次恢复各层的实际结果（issue #23）。
 *
 * 只记录“真的生效”的层：`powerResetOk` 在 power-reset 返回 Unknown command 时
 * 必须是 false；`activityLaunched` 只表示 `startActivity` 没有抛异常（系统仍可能
 * 拒绝后台启动），措辞上不能写“已点亮”。
 */
internal data class RestoreReport(
    val powerResetOk: Boolean,
    val imeRestored: Boolean,
    val wakeUpOk: Boolean,
    val keyeventOk: Boolean,
    val activityLaunched: Boolean,
    val wakeLockHeld: Boolean,
) {
    /** 是否至少有一层可能把物理屏点亮（IME 不算显示层）。 */
    val anyDisplayLayerOk: Boolean
        get() = powerResetOk || wakeUpOk || keyeventOk || activityLaunched

    /** 汇总成一行，用于实机日志回签。 */
    fun describe(): String = listOfNotNull(
        if (powerResetOk) "power-reset" else null,
        if (wakeUpOk) "wakeUp" else null,
        if (keyeventOk) "keyevent224" else null,
        if (activityLaunched) "Activity" else null,
        if (wakeLockHeld) "WakeLock" else null,
        if (imeRestored) "IME" else null,
    ).joinToString("+").ifEmpty { "无" }
}

/** `cmd display` 对 `power-reset` 的支持程度（issue #221）。 */
internal enum class PowerResetCapability { SUPPORTED, UNSUPPORTED, UNKNOWN }

/**
 * `power-reset` 能力的进程内缓存（issue #221）。
 *
 * 现场：该 ROM 的 `cmd display` 没有 `power-reset`，每次断开恢复都盲跑一次、
 * 白等 3.4–4.2s 才拿到 `Unknown command`。引擎侧 `DisplayPowerController` 早已用
 * `cmd display help` 探测并缓存；手机端补上同样的能力探测，负结论按**进程**缓存
 * （[PhoneDisplayRestorer] 会随 Service 重建，实例级缓存不够）。
 *
 * `UNKNOWN`（help 拿不到）不写入缓存，下次仍尝试，避免把可用 ROM 误跳过。
 */
internal class PowerResetCapabilityCache {
    @Volatile
    private var cached: PowerResetCapability? = null

    fun get(): PowerResetCapability? = cached

    fun put(capability: PowerResetCapability) {
        if (capability != PowerResetCapability.UNKNOWN) cached = capability
    }

    companion object {
        /** 生产用进程级实例；单测各自 new 一个，互不污染。 */
        val processWide = PowerResetCapabilityCache()
    }
}

/**
 * 物理屏恢复的逐层执行器（issue #23，从 [PhoneDisplayRestorer] 抽出）。
 *
 * 所有副作用都通过构造参数注入，因此可以在纯 JVM 单测里覆盖最关键的场景：
 * **wakeUp 反射失败 + `power-reset` 返回 `Unknown command` 时，仍然要启动
 * Activity 兜底，且日志里不得出现成功文案**。
 *
 * 判定纪律：每条 shell 命令都经 [ShellVerdict.wrap] 带上退出码标记，只有
 * `rc == 0` 且不含 `Unknown command` 才算成功；失败打 W 并携带原始输出。
 * 「恢复成功」级别的 I 日志只在对应层真的生效时出现。
 *
 * 引擎优雅停止（哨兵 + 等退出 + 强杀）仍留在 [PhoneDisplayRestorer]：那一段
 * 依赖协程、探测与超时，与本类的“判定并记录”职责无关。
 */
internal class DisplayRestoreSequence(
    private val shell: (String) -> ShellOutcome?,
    private val wakeUpFn: () -> Unit,
    private val launchActivityFn: () -> Unit,
    private val wakeLockFn: () -> Unit,
    private val log: RestoreLog,
    /** `power-reset` 能力缓存；生产用进程级实例，单测可注入独立实例（issue #221）。 */
    private val capabilityCache: PowerResetCapabilityCache = PowerResetCapabilityCache.processWide,
) {
    /**
     * @param imeToRestore 已解析出的原输入法（null 表示无需恢复）。
     */
    fun run(imeToRestore: String?): RestoreReport {
        // 第 1 层：Shizuku — 物理屏 power-reset。
        // 2026-10-27 现场（MEIZU 20 Inf）：该 ROM 的 `cmd display` 没有 power-reset
        // 子命令，返回 `Unknown command: power-reset`；旧实现不看返回值，照样打
        // "Physical display restored via Shizuku"。现在按退出码 + 文本判定。
        //
        // issue #221：先按 `cmd display help` 探测能力（进程内缓存），ROM 不支持时
        // 直接跳过，不再盲跑一次、白等 3.4–4.2s 才拿到 `Unknown command`。
        val capability = probePowerResetCapability()
        var powerResetOk = false
        if (capability == PowerResetCapability.UNSUPPORTED) {
            log.w("power-reset skipped: cmd display help has no power-reset on this ROM — not waiting (issue #221)")
        } else {
            val startedAt = System.nanoTime()
            val resetOutcome = shell(ShellVerdict.wrap(POWER_RESET_COMMAND))
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            powerResetOk = ShellVerdict.succeeded(resetOutcome?.stdout, resetOutcome?.stderr)
            when {
                resetOutcome == null -> log.w("Shizuku unavailable — relying on framework wake layers only")
                powerResetOk -> log.i("Physical display restored via power-reset ($POWER_RESET_COMMAND, ${elapsedMs}ms)")
                else -> {
                    val detail = ShellVerdict.detail(resetOutcome.stdout, resetOutcome.stderr)
                    log.w("power-reset did not take effect after ${elapsedMs}ms: $detail")
                    // 探针拿不到 help 时（UNKNOWN），运行期这句 `Unknown command` 就是
                    // 负结论；写进进程缓存，后续会话直接跳过（issue #221）。
                    if (detail.contains(ShellVerdict.UNKNOWN_COMMAND)) {
                        capabilityCache.put(PowerResetCapability.UNSUPPORTED)
                    }
                }
            }
        }

        // 第 1b 层：IME 恢复（与显示无关，不参与 anyDisplayLayerOk）。
        var imeRestored = false
        if (ImeRestore.shouldRestoreIme(imeToRestore)) {
            val imeOutcome = shell(ShellVerdict.wrap(ImeRestore.imeRestoreCommandLine(imeToRestore!!)))
            imeRestored = ShellVerdict.succeeded(imeOutcome?.stdout, imeOutcome?.stderr)
            if (imeRestored) {
                log.i("Original IME restored via Shizuku: $imeToRestore")
            } else {
                log.w("IME restore failed: ${ShellVerdict.detail(imeOutcome?.stdout, imeOutcome?.stderr)}")
            }
        }

        // 第 2 层：PowerManager.wakeUp()。隐藏 API 下反射通常取不到该方法
        // （@SystemApi），这里失败属于常态，必须打 W 并降级，而不是只打 D。
        var wakeUpOk = false
        try {
            wakeUpFn()
            wakeUpOk = true
            log.i("Display wakeUp() succeeded")
        } catch (e: Exception) {
            log.w("wakeUp() unavailable: ${e.message} — falling back to KEYCODE_WAKEUP")
        }

        // 第 3 层（新增）：wakeUp 反射不可用时的 shell 降级 —— KEYCODE_WAKEUP。
        var keyeventOk = false
        if (!wakeUpOk) {
            val keyOutcome = shell(ShellVerdict.wrap(KEYEVENT_WAKEUP_COMMAND))
            keyeventOk = ShellVerdict.succeeded(keyOutcome?.stdout, keyOutcome?.stderr)
            if (keyeventOk) {
                log.i("Sent KEYCODE_WAKEUP ($KEYEVENT_WAKEUP_COMMAND)")
            } else {
                log.w("KEYCODE_WAKEUP failed: ${ShellVerdict.detail(keyOutcome?.stdout, keyOutcome?.stderr)}")
            }
        }

        // 第 4 层：启动 MainActivity + FLAG_TURN_SCREEN_ON。
        var activityLaunched = false
        try {
            launchActivityFn()
            activityLaunched = true
            log.i("MainActivity launch requested (FLAG_TURN_SCREEN_ON)")
        } catch (e: Exception) {
            log.w("Activity launch for wake failed: ${e.message}")
        }

        // 第 5 层：WakeLock + ACQUIRE_CAUSES_WAKEUP。
        var wakeLockHeld = false
        try {
            wakeLockFn()
            wakeLockHeld = true
        } catch (e: Exception) {
            log.w("forceWakeScreen error: ${e.message}")
        }

        return RestoreReport(
            powerResetOk = powerResetOk,
            imeRestored = imeRestored,
            wakeUpOk = wakeUpOk,
            keyeventOk = keyeventOk,
            activityLaunched = activityLaunched,
            wakeLockHeld = wakeLockHeld,
        )
    }

    /**
     * 探测 `cmd display help` 是否列出 `power-reset`（issue #221），结论按进程缓存。
     *
     * 与引擎侧 `DisplayPowerController.powerResetCapability()` 同款语义：
     * help 拿不到（Shizuku 不可用 / 空输出）返回 [PowerResetCapability.UNKNOWN] 且
     * **不缓存**，点亮链仍然尝试，避免把可用 ROM 误跳过。
     */
    private fun probePowerResetCapability(): PowerResetCapability {
        capabilityCache.get()?.let { return it }
        val startedAt = System.nanoTime()
        val helpOutcome = shell("cmd display help")
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val help = listOfNotNull(helpOutcome?.stdout, helpOutcome?.stderr)
            .joinToString("\n")
            .trim()
        val capability = when {
            help.isEmpty() -> PowerResetCapability.UNKNOWN
            help.contains("power-reset") -> PowerResetCapability.SUPPORTED
            else -> PowerResetCapability.UNSUPPORTED
        }
        capabilityCache.put(capability)
        if (helpOutcome == null) {
            log.d("power-reset capability unknown: Shizuku unavailable — still attempting")
        } else {
            log.d("power-reset capability=${capability.name.lowercase()} (cmd display help, ${elapsedMs}ms)")
        }
        return capability
    }

    companion object {
        /** power-reset 的点亮命令（不再带 `2>/dev/null`，退出码由 [ShellVerdict] 读）。 */
        const val POWER_RESET_COMMAND = "cmd display power-reset 0"

        /** KEYCODE_WAKEUP：`wakeUp()` 反射不可用时的 shell 降级。 */
        const val KEYEVENT_WAKEUP_COMMAND = "input keyevent 224"
    }
}
