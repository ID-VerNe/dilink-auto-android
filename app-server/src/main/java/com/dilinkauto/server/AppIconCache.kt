package com.dilinkauto.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Car-side icon cache — decodes and resizes icons once, then serves them instantly.
 *
 * The phone sends high-resolution source PNGs (192x192). When APP_LIST arrives,
 * [prepareAll] decodes and resizes every icon to the grid size on a background
 * thread. After that, [getPrepared] is an O(1) ConcurrentHashMap lookup — zero
 * coroutines, zero I/O, zero decode during scroll.
 */
class AppIconCache(private val cacheDir: File) {

    private val sourceCache = ConcurrentHashMap<String, ByteArray>()
    // Grid-size prepared bitmaps. Sole writer is [prepareAll], keyed by packageName.
    // [get] no longer mutates this map — it returns its own Bitmap at the requested
    // size without polluting the grid-size cache (which previously caused navbar/
    // notification icons rendered at 40dp to overwrite the 64dp grid bitmap).
    private val prepared = ConcurrentHashMap<String, ImageBitmap>()

    /** Incremented after each prepareAll() completes — UI observes this to recompose. */
    @Volatile var preparedVersion: Int = 0
        private set

    init { cacheDir.mkdirs() }

    /**
     * Full decode+resize path — used by NotificationScreen and NavBar (few icons).
     * Returns a Bitmap at the requested size. Does NOT write into [prepared] — the
     * grid cache is owned by [prepareAll] at the grid size only.
     */
    fun get(packageName: String, sizePx: Int): Bitmap? {
        val source = sourceCache[packageName] ?: loadSourceFromDisk(packageName) ?: return null
        return try {
            val decoded = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return null
            Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true)
        } catch (_: Exception) { null }
    }

    /** Store the high-resolution source PNG received from the phone. */
    fun putSource(packageName: String, pngBytes: ByteArray) {
        if (pngBytes.isEmpty()) return
        sourceCache[packageName] = pngBytes
        try { File(cacheDir, "${packageName}_src.png").writeBytes(pngBytes) } catch (_: Exception) {}
    }

    /**
     * Synchronous O(1) lookup — returns a ready-to-render [ImageBitmap] or null.
     * Call ONLY after [prepareAll] has finished.
     */
    fun getPrepared(packageName: String): ImageBitmap? = prepared[packageName]

    /** Number of prepared icons ready for instant rendering. */
    fun preparedCount(): Int = prepared.size

    /**
     * Decode + resize all icons on the current thread.
     * Call from a background coroutine BEFORE the grid becomes visible.
     *
     * @param apps list of all apps (reads source PNG from cache/disk for each)
     * @param sizePx target icon size in pixels (e.g. 64dp in px)
     * @return how many icons were successfully prepared
     */
    fun prepareAll(apps: List<com.dilinkauto.protocol.AppInfo>, sizePx: Int): Int {
        var count = 0
        apps.forEach { app ->
            val key = app.packageName
            if (prepared.containsKey(key)) {
                count++
                return@forEach
            }
            val source = sourceCache[key] ?: loadSourceFromDisk(key) ?: return@forEach
            try {
                val decoded = BitmapFactory.decodeByteArray(source, 0, source.size) ?: return@forEach
                val resized = Bitmap.createScaledBitmap(decoded, sizePx, sizePx, true)
                prepared[key] = resized.asImageBitmap()
                count++
            } catch (_: Exception) {}
        }
        if (count > 0) preparedVersion++
        return count
    }

    private fun loadSourceFromDisk(packageName: String): ByteArray? {
        val f = File(cacheDir, "${packageName}_src.png")
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            sourceCache[packageName] = bytes
            bytes
        } catch (_: Exception) { null }
    }

    /** Remove all cached data for a package (e.g. when app is uninstalled). */
    fun evict(packageName: String) {
        sourceCache.remove(packageName)
        prepared.remove(packageName)
        File(cacheDir, "${packageName}_src.png").delete()
    }
}
