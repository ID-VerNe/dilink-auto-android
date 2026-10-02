package com.dilinkauto.client

import android.os.Environment
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * File-based logger that bypasses Android logcat filtering.
 * Writes to /sdcard/DiLinkAuto/client.log and also calls android.util.Log.
 *
 * On rotate(), the current log is renamed with a timestamp and a fresh log starts.
 * Old session logs accumulate in the folder (client-YYYYMMDD-HHmmss.log).
 * Thread-safe: uses a lock-free queue drained by a single writer thread.
 */
object FileLog {

    /** Toggled from Settings. When false, no file writes or logcat output. */
    @Volatile var enabled = true

    private val queue = ConcurrentLinkedQueue<String>()
    @Volatile private var writer: FileWriter? = null
    // SimpleDateFormat is NOT thread-safe — the writer thread formats all
    // timestamps, so a single shared instance is safe here. Callers only ever
    // queue raw message strings (no formatting on the calling thread).
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logDir = File(Environment.getExternalStorageDirectory(), "DiLinkAuto")
    private val logFile = File(logDir, "client.log")

    init {
        try {
            logDir.mkdirs()
            writer = FileWriter(logFile, true) // append on process start
            writer?.write("=== DiLink Auto Client log started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n")
            writer?.flush()
        } catch (e: Exception) {
            Log.e("FileLog", "Failed to open log file: ${e.message}")
        }

        Thread({
            while (true) {
                try {
                    val line = queue.poll()
                    if (line != null) {
                        // Format on the writer thread only — SimpleDateFormat is
                        // not thread-safe, and this is the single consumer.
                        val ts = dateFormat.format(Date())
                        val parts = line.split("␞", limit = 3)
                        val (level, tag, msg) = if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else Triple("I", "FileLog", line)
                        writer?.write("[$ts][$level][$tag] $msg")
                        writer?.write("\n")
                        writer?.flush()
                    } else {
                        Thread.sleep(200) // reduced poll rate for low-end devices
                    }
                } catch (_: Exception) {}
            }
        }, "FileLog").apply { isDaemon = true; start() }
    }

    /**
     * Rotate: rename current log with timestamp, start fresh.
     * Keeps at most 10 log files (9 archived + current). Oldest are deleted.
     */
    /**
     * Read enabled state from SharedPreferences. Call on service start.
     * Default: ON for debug/pre-release builds, OFF for release builds.
     * Once user explicitly toggles, that choice persists regardless of build type.
     */
    fun loadEnabled(prefs: android.content.SharedPreferences) {
        val userSet = prefs.getBoolean("log_enabled_user_set", false)
        enabled = if (userSet) {
            prefs.getBoolean("log_enabled", false)
        } else {
            com.dilinkauto.client.BuildConfig.DEBUG  // true for pre-release, false for release
        }
    }

    fun rotate() {
        queue.clear()
        try {
            writer?.close()
            if (logFile.exists() && logFile.length() > 0) {
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val archiveFile = File(logDir, "client-$ts.log")
                logFile.renameTo(archiveFile)
            }
            // Prune: keep only 9 archived logs (+ the new current = 10 total)
            val archived = logDir.listFiles { f -> f.name.startsWith("client-") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.name }
            archived?.drop(9)?.forEach { it.delete() }

            writer = FileWriter(logFile, false)
            writer?.write("=== DiLink Auto Client log started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} ===\n")
            writer?.flush()
        } catch (e: Exception) {
            Log.e("FileLog", "Failed to rotate log file: ${e.message}")
        }
    }

    fun i(tag: String, msg: String) { if (enabled) { Log.i(tag, msg); write("I", tag, msg) } }
    fun d(tag: String, msg: String) { if (enabled) { Log.d(tag, msg); write("D", tag, msg) } }
    fun w(tag: String, msg: String) { if (enabled) { Log.w(tag, msg); write("W", tag, msg) } }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (!enabled) return
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        write("E", tag, "$msg${t?.let { " | ${it.message}" } ?: ""}")
    }

    fun zipLogs(): File? {
        return try {
            val zipFile = File(logDir, "dilinkauto-logs.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                val logFiles = logDir.listFiles { f -> f.name.endsWith(".log") }
                if (logFiles != null) {
                    for (file in logFiles) {
                        zos.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                // Include VD server log if it exists
                val vdLog = File("/data/local/tmp/vd-server.log")
                if (vdLog.exists()) {
                    zos.putNextEntry(ZipEntry("vd-server.log"))
                    vdLog.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zipFile
        } catch (e: Exception) {
            Log.e("FileLog", "Failed to zip logs: ${e.message}")
            null
        }
    }

    fun logDirectory(): File = logDir

    private fun write(level: String, tag: String, msg: String) {
        // No timestamp formatting on the calling thread — queue the raw pieces and
        // let the single writer thread format with the shared SimpleDateFormat.
        queue.add("$level␞$tag␞$msg")
    }
}
