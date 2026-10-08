package com.dilinkauto.client

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import com.dilinkauto.protocol.VdProbeResult
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.FileInputStream

/**
 * Manages Shizuku lifecycle and provides shell-level command execution.
 *
 * Uses the Shizuku AIDL interface ([IShizukuService.newProcess])
 * to create processes with shell (UID 2000) privileges.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val SHIZUKU_WEB_URL = "https://github.com/RikkaApps/Shizuku/releases"
    private const val REQUEST_CODE = 0

    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    var isInstalled: Boolean = false
        private set

    private var listenersRegistered = false

    fun init(context: Context) {
        if (listenersRegistered) return
        listenersRegistered = true

        try {
            isInstalled = try {
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

            Shizuku.addBinderReceivedListener {
                FileLog.i(TAG, "Shizuku binder received")
                checkPermission()
            }

            Shizuku.addBinderDeadListener {
                isAvailable = false
                FileLog.i(TAG, "Shizuku binder dead")
            }

            Shizuku.addRequestPermissionResultListener { code, result ->
                if (code == REQUEST_CODE) {
                    isAvailable = result == PackageManager.PERMISSION_GRANTED
                    FileLog.i(TAG, "Shizuku permission: ${if (isAvailable) "granted" else "denied"}")
                }
            }

            if (isInstalled) checkPermission()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku init failed: ${e.message}")
        }
    }

    fun checkPermission(): Boolean {
        isAvailable = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        return isAvailable
    }

    /**
     * Request Shizuku permission. Only call from an Activity context.
     */
    fun requestPermission() {
        if (!isInstalled) return
        if (isAvailable) return
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                isAvailable = true
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku permission request failed: ${e.message}")
        }
    }

    /**
     * Open the Shizuku app so the user can manage authorization manually.
     */
    fun openShizukuApp(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Shizuku: ${e.message}")
        }
    }

    /**
     * Send the user somewhere they can install Shizuku (audit UX-13: the
     * not-installed settings row looked tappable and silently did nothing).
     *
     * Prefers the local store app via the `market:` scheme — that resolves on
     * Chinese ROMs without Google Play — and falls back to the project's
     * GitHub releases, which is also where the ADB start-up instructions live.
     */
    fun openShizukuStorePage(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
            return
        } catch (e: Exception) {
            Log.w(TAG, "No market app for Shizuku, opening web page: ${e.message}")
        }
        try {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_WEB_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(web)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Shizuku install page: ${e.message}")
        }
    }

    private fun getService(): IShizukuService? {
        return try {
            val binder: IBinder = Shizuku.getBinder()
                ?: return null
            if (!binder.pingBinder()) return null
            IShizukuService.Stub.asInterface(binder)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Execute a shell command and return stdout + stderr combined.
     * Blocks until the command completes (or the 30s deadline expires).
     */
    fun execAndWait(command: String): String? {
        if (!isAvailable) return null
        return try {
            val service = getService() ?: return null
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)

            // Duplicate FDs — ParcelFileDescriptors from binder transactions
            // can become invalid (EBADF) when the original is garbage collected.
            // IRemoteProcess.getInputStream/getErrorStream return fresh PFDs each
            // call, so dup+close is unnecessary; just hold the references and close
            // them in finally.
            val stdoutFd = process.inputStream
            val stderrFd = process.errorStream

            // Drain stdout/stderr concurrently so a process that writes more
            // than the pipe buffer to stderr (64KB) can't deadlock on a full pipe
            // while we are still blocked reading stdout.
            val stderrBuf = StringBuilder()
            val drainThread = Thread({
                try {
                    FileInputStream(stderrFd.fileDescriptor).bufferedReader().use { reader ->
                        val c = CharArray(4096)
                        while (true) {
                            val n = reader.read(c)
                            if (n < 0) break
                            stderrBuf.append(c, 0, n)
                        }
                    }
                } catch (_: Exception) {}
            }, "ShizukuStderr").apply { isDaemon = true }
            drainThread.start()

            val stdout = try {
                FileInputStream(stdoutFd.fileDescriptor).bufferedReader().use { it.readText() }
            } catch (_: Exception) { "" }

            // Cap waitFor so a hung command can't block the caller indefinitely.
            // IRemoteProcess.waitForTimeout returns true if the process exited
            // within the timeout; fall back to destroy() if it did not.
            val completed = waitForWithDeadline(process, 30_000L)
            drainThread.join(2000)
            stdoutFd.close()
            stderrFd.close()

            if (!completed) {
                FileLog.w(TAG, "Shizuku exec timed out: $command")
                process.destroy()
            }
            if (stdout.isNotEmpty()) stdout else stderrBuf.toString()
        } catch (e: Exception) {
            FileLog.w(TAG, "Shizuku exec failed: ${e.message}")
            null
        }
    }

    /** Wait for the remote process with a deadline. Returns true if it exited. */
    private fun waitForWithDeadline(process: IRemoteProcess, deadlineMs: Long): Boolean {
        return try {
            process.waitForTimeout(deadlineMs, java.util.concurrent.TimeUnit.MILLISECONDS.name)
        } catch (_: Exception) { false }
    }

    /**
     * Execute a shell command in the background (fire and forget).
     *
     * The command is wrapped as `sh -c "<cmd>"` and the caller is expected to
     * include its own `&` (the vd-server deploy path builds `setsid ...
     * >>log 2>&1 &` via [com.dilinkauto.protocol.VdDeploy.commandLine] with
     * background=true). We do NOT strip a trailing `&` and re-add one: that
     * earlier form turned `setsid app_process ... &` into `setsid app_process
     * ... & &`, a syntax error in some shells, and stripped a `&` the
     * caller deliberately placed. Passing the command through verbatim lets
     * the caller control backgrounding semantics (setsid, nohup, etc.).
     */
    fun execBackground(command: String) {
        if (!isAvailable) return
        try {
            val service = getService() ?: return
            service.newProcess(arrayOf("sh", "-c", command.trim()), null, null)
        } catch (e: Exception) {
            FileLog.w(TAG, "Shizuku execBackground failed: ${e.message}")
        }
    }

    /**
     * One liveness probe of the vd-server engine, in the output form
     * ([com.dilinkauto.protocol.VdDeploy.probeCommand]) — Shizuku's
     * `execAndWait` exposes stdout but not the exit code, so this is the
     * Shizuku-side counterpart of the car/desktop exit-code probe.
     *
     * Returns [VdProbeResult.UNKNOWN] when Shizuku is unavailable or the probe
     * itself fails; the deploy sequence treats that as "accept the exit" so a
     * dead transport never stalls deployment. The convergence rules (two
     * consecutive GONE) live in [com.dilinkauto.protocol.vdAwaitExit] — this
     * class intentionally does not implement its own wait loop anymore.
     */
    fun probeVdServer(): VdProbeResult {
        if (!isAvailable) return VdProbeResult.UNKNOWN
        val out = try {
            execAndWait(com.dilinkauto.protocol.VdDeploy.probeCommand)
        } catch (_: Exception) {
            null
        } ?: return VdProbeResult.UNKNOWN
        return if (out.trim().endsWith("Y")) VdProbeResult.ALIVE else VdProbeResult.GONE
    }
}
