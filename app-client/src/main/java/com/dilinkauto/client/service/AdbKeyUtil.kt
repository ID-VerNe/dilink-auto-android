package com.dilinkauto.client.service

import com.dilinkauto.client.FileLog
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADB key-pair + Dadb-create helpers shared by the car-APK installer
 * ([CarAppInstaller]) and the self-update installer ([ApkInstaller.tryDadbInstall]).
 *
 * Both sites previously carried byte-identical key-pair generation blocks and
 * the same `Future.get(timeout, SECONDS)` pattern around `Dadb.create()` (which
 * does blocking socket I/O that coroutine cancellation cannot interrupt). They
 * diverged on the timeout (15s for the user-initiated car install, 10s for the
 * auto-update fallback); the shared helper takes the timeout as a parameter so
 * each call site keeps its own value while the body lives in one place.
 */
internal object AdbKeyUtil {

    /**
     * Read or generate the Dadb key pair at `[filesDir]/adbkey{,.pub}`.
     * Files are kept in the app's internal storage so they survive across
     * sessions and are reachable by Dadb.
     */
    fun ensureAdbKeyPair(filesDir: File): AdbKeyPair {
        val privKey = File(filesDir, "adbkey")
        val pubKey = File(filesDir, "adbkey.pub")
        if (!privKey.exists()) {
            filesDir.mkdirs()
            AdbKeyPair.generate(privKey, pubKey)
        }
        return AdbKeyPair.read(privKey, pubKey)
    }

    /**
     * Connect to [host]:[port] via Dadb with a hard [timeoutSeconds] deadline.
     *
     * `Dadb.create()` does blocking socket I/O that coroutine cancellation
     * cannot interrupt, so the timeout is enforced via `Future.get` on a
     * dedicated single-thread executor (always shut down in finally). Returns
     * null on timeout (the car auth dialog is likely pending) or any failure.
     * Caller owns the returned [Dadb] lifecycle (close it after).
     */
    fun dadbCreateWithTimeout(tag: String, host: String, port: Int, keyPair: AdbKeyPair, timeoutSeconds: Long): Dadb? {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val future = executor.submit<Dadb> { Dadb.create(host, port, keyPair) }
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                FileLog.w(tag, "Dadb.create() timed out after ${timeoutSeconds}s — likely waiting for auth dialog")
                null
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
