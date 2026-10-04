package com.dilinkauto.server

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.dilinkauto.protocol.DeviceInfo
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncaught exception handler for the car app.
 *
 * On crash: saves a crash report with stack trace and device info to
 * [filesDir]/crash-pending.log. The report is sent to the phone on the next
 * successful connection via carLogSend.
 */
object CarCrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CarCrashHandler"
    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    private const val CRASH_FILE = "crash-pending.log"

    /** Set by CarConnectionService so the handler can flush pending crashes on connect. */
    @Volatile var pendingCrashFile: File? = null
    @Volatile var logSink: ((String) -> Unit)? = null

    fun install(context: Context) {
        pendingCrashFile = File(context.filesDir, CRASH_FILE)
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val crashFile = pendingCrashFile
        val report = buildReport(thread, throwable)

        // Always write to file
        if (crashFile != null) {
            try {
                crashFile.writeText(report)
            } catch (_: Exception) {}
        }

        // Log to logcat as last resort
        try { Log.e("CarCrashHandler", report) } catch (_: Exception) {}

        // Attempt to send via TCP log sink if connected — brief wait for delivery
        val sink = logSink
        if (sink != null) {
            try {
                report.lines().forEach { sink(it) }
                Thread.sleep(1500) // allow time for TCP flush
            } catch (_: Exception) {}
        }

        // Hand off to original handler (or kill process)
        if (originalHandler != null && originalHandler !== this) {
            originalHandler.uncaughtException(thread, throwable)
        } else {
            // No original handler — kill the process ourselves
            try { Thread.sleep(500) } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    /**
     * Returns any pending crash report and moves the file to an archive
     * so it isn't re-sent on every connection.
     */
    fun consumePendingCrash(): String? {
        val f = pendingCrashFile ?: return null
        if (!f.exists()) return null
        return try {
            val content = f.readText()
            // Archive so we don't send it again
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            f.renameTo(File(f.parent, "crash-sent-$ts.log"))
            content
        } catch (_: Exception) { null }
    }

    fun buildDeviceInfo(context: Context): String {
        // Device Info block is shared with the phone-side ConnectionService via
        // [com.dilinkauto.protocol.DeviceInfo]; the car crash report extends it
        // with the Memory section and the memThreshold line.
        val sb = StringBuilder()
        sb.appendLine(DeviceInfo.buildDeviceInfoBlock(context, includeMemory = true))

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            sb.appendLine("memThreshold=${ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.threshold}")
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun logToSink(msg: String) {
        logSink?.invoke(msg) ?: Log.i(TAG, msg)
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        return buildString {
            appendLine("=== DiLink Auto Car Crash Report $ts ===")
            appendLine("thread=${thread.name}")
            appendLine()
            append(sw.toString())
            appendLine()
            appendLine("── Process State ──")
            appendLine("pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        }
    }
}
