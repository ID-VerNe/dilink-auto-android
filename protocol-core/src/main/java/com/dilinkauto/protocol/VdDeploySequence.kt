package com.dilinkauto.protocol

import kotlinx.coroutines.delay

/**
 * Unified "deploy the vd-server" sequence shared by every deploy site.
 *
 * The sequence itself is: **kill → wait for the old engine to exit (force-kill
 * + re-wait on timeout) → launch**.
 *
 * Four sites previously re-implemented this: car USB/TCP ADB
 * (`VdServerDeployer.deploy`), car dev-mode TCP ADB (`deployDirect`), phone
 * Shizuku (`ConnectionService.startVdServerViaShizuku`) and desktop adb.exe
 * (`AdbDeployer.deploy`). The command strings were already single-sourced in
 * [VdDeploy], but the *orchestration* — and especially the "wait for exit"
 * convergence rule — had drifted into three different behaviours
 * (retry-once / one-shot stdout probe / two-consecutive-gone). This file is
 * the single definition; callers only supply a [VdDeployExecutor].
 */

/**
 * Result of one liveness probe of the vd-server process.
 *
 * [UNKNOWN] means "the probe could not be executed at all" (transport dead,
 * command failed) — as opposed to [GONE], which is a confirmed absence. The
 * distinction matters because transports fail in different ways and the old
 * per-site code treated "cannot probe" inconsistently.
 */
enum class VdProbeResult {
    /** Process confirmed alive. */
    ALIVE,

    /** Process confirmed gone. */
    GONE,

    /**
     * Could not probe (transport down / command failed). Callers treat this as
     * "accept the exit immediately" so deployment never stalls on a dead
     * transport — the timeout + force-kill path remains the safety net for
     * genuinely wedged engines.
     */
    UNKNOWN,
}

/**
 * Transport abstraction for the deploy sequence. Implementations wrap one
 * shell channel: car USB/TCP ADB, car dev-mode [RemoteAdbController], phone
 * Shizuku, or desktop adb.exe.
 *
 * Functions are suspend so the sequence can be driven from a coroutine;
 * implementations may block internally (the callers run them on IO threads).
 */
interface VdDeployExecutor {
    /** Run a command to completion (kill / stop / probe setup). */
    suspend fun shellSync(command: String)

    /**
     * Launch the engine. Return false when the transport reports a launch
     * failure; transports that cannot observe the launch (Shizuku
     * `execBackground` is fire-and-forget) return true.
     */
    suspend fun launch(command: String): Boolean

    /** One liveness probe of the engine. */
    suspend fun probe(): VdProbeResult
}

/** Outcome of [vdRunDeploySequence]. */
data class VdDeployOutcome(
    /** True when the graceful wait timed out and [VdDeploy.stopCommand] was used. */
    val forcedKill: Boolean,
    /** launch() result — see [VdDeployExecutor.launch]. */
    val launched: Boolean,
)

/** Shared timing/convergence constants for the deploy sequence. */
object VdDeploySequence {
    /** Total wait budget for the old engine to exit, per attempt. */
    const val EXIT_WAIT_TIMEOUT_MS = 3_000L

    /** Poll interval while waiting for the old engine to exit. */
    const val EXIT_WAIT_POLL_MS = 150L

    /**
     * Consecutive GONE probes required to accept the exit. Two (not one)
     * because transports can report a transient false on a hiccup; one
     * probe would then green-light a launch that races the still-live engine.
     */
    const val EXIT_CONFIRMATIONS = 2
}

/**
 * Wait until the old engine has exited.
 *
 * Convergence rule (single definition, used by all four deploy sites):
 *  - ALIVE   → reset the gone-counter;
 *  - GONE    → gone-counter++, [confirmations] consecutive GONE accept the exit;
 *  - UNKNOWN → accept immediately (cannot probe — do not stall deployment).
 *
 * @return true when the exit is confirmed (or the probe is unavailable);
 *         false on timeout — the caller should force-kill and re-wait.
 * @param now   injectable clock (tests use a fake to keep the wait instant)
 * @param sleep injectable sleeper (tests substitute a no-op fake)
 */
suspend fun vdAwaitExit(
    executor: VdDeployExecutor,
    timeoutMs: Long = VdDeploySequence.EXIT_WAIT_TIMEOUT_MS,
    pollMs: Long = VdDeploySequence.EXIT_WAIT_POLL_MS,
    confirmations: Int = VdDeploySequence.EXIT_CONFIRMATIONS,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    var goneCount = 0
    val deadline = now() + timeoutMs
    while (now() < deadline) {
        when (executor.probe()) {
            VdProbeResult.UNKNOWN -> return true
            VdProbeResult.ALIVE -> goneCount = 0
            VdProbeResult.GONE -> if (++goneCount >= confirmations) return true
        }
        sleep(pollMs)
    }
    return false
}

/**
 * The unified deploy sequence:
 *
 *  1. [VdDeploy.DeployPlan.killCommand] — graceful kill of the old engine;
 *  2. [vdAwaitExit] — wait for a real exit; on timeout, [VdDeploy.stopCommand]
 *     (two-stage SIGTERM → SIGKILL) and wait once more;
 *  3. [VdDeploy.DeployPlan.launchCommand] — launch the replacement.
 *
 * Two live engines race for the same VirtualDisplay / DTA / 9638-9639 binds
 * and the loser never runs cleanup() — one leaked VD per reconnect. That is
 * why step 2 exists and why it is shared: a divergence here (see the file doc)
 * is exactly how the leak was re-introduced once before.
 */
suspend fun vdRunDeploySequence(
    plan: VdDeploy.DeployPlan,
    executor: VdDeployExecutor,
    timeoutMs: Long = VdDeploySequence.EXIT_WAIT_TIMEOUT_MS,
    pollMs: Long = VdDeploySequence.EXIT_WAIT_POLL_MS,
    now: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
): VdDeployOutcome {
    executor.shellSync(plan.killCommand)
    var forcedKill = false
    if (!vdAwaitExit(executor, timeoutMs, pollMs, now = now, sleep = sleep)) {
        forcedKill = true
        executor.shellSync(VdDeploy.stopCommand)
        vdAwaitExit(executor, timeoutMs, pollMs, now = now, sleep = sleep)
    }
    val launched = executor.launch(plan.launchCommand)
    return VdDeployOutcome(forcedKill = forcedKill, launched = launched)
}
