package com.dilinkauto.client.service

import android.content.res.AssetManager
import com.dilinkauto.client.FileLog
import java.io.File
import java.util.zip.CRC32

/**
 * Unpacks bundled assets to disk, skipping the write when the on-disk copy
 * already matches.
 *
 * Extracted from [ConnectionService]: `extractAsset` and
 * `ensureVdServerJarCurrent` each independently implemented the same
 * read-asset → CRC32 → compare-with-target → `writeAtomically` sequence, so a
 * fix to one silently skipped the other (docs/audit-srp-dry.md DRY-4).
 *
 * The CRC comparison is the reason this exists at all rather than a plain copy.
 * The deployed `vd-server.jar` outlives the process while [extract] runs only
 * once per process, so without this check `app_process` can silently load a
 * stale engine — the app still runs and still logs, it just runs old code.
 */
internal class AssetDeployer(private val assets: AssetManager) {

    /**
     * CRC of the on-disk file if the write happened or the file was already
     * current; `null` if the asset could not be read or written.
     */
    sealed class Result {
        /** Target now matches the asset. [crc] is its CRC. */
        data class Current(val crc: Long) : Result()

        /** Target was written from a newer/different asset. [crc] is its CRC. */
        data class Written(val crc: Long, val bytes: Int) : Result()

        /** The asset could not be read or written. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Ensures [target] holds the current contents of [assetName], writing only
     * when the CRCs differ.
     */
    fun extract(assetName: String, target: File): Result {
        val assetBytes = try {
            assets.open(assetName).use { it.readBytes() }
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to read asset $assetName: ${e.message}")
            return Result.Failed("read: ${e.message}")
        }

        val assetCrc = crc32(assetBytes)

        // mkdirs must precede the existence check: the jar lands in
        // /sdcard/DiLinkAuto, which does not exist on a fresh install.
        target.parentFile?.mkdirs()
        if (target.exists()) {
            val fileCrc = try {
                crc32(target.readBytes())
            } catch (_: Exception) {
                null // unreadable target — fall through and overwrite
            }
            if (fileCrc == assetCrc) {
                FileLog.i(TAG, "$assetName up-to-date (crc=$assetCrc)")
                return Result.Current(assetCrc)
            }
        }

        return try {
            writeAtomically(target, assetBytes)
            FileLog.i(
                TAG,
                "$assetName deployed to ${target.absolutePath} (${assetBytes.size} bytes, crc=$assetCrc)"
            )
            Result.Written(assetCrc, assetBytes.size)
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to extract $assetName: ${e.message}")
            Result.Failed("write: ${e.message}")
        }
    }

    /**
     * @return the jar's CRC when it is known to be current (or was just
     *   refreshed), or `-1` when the state is unknown and the caller should
     *   decide whether to abort.
     */
    fun ensureCurrent(assetName: String, target: File): Long =
        when (val r = extract(assetName, target)) {
            is Result.Current -> r.crc
            is Result.Written -> {
                FileLog.i(TAG, "VD jar refreshed: ${target.absolutePath} (${r.bytes} bytes, crc=${r.crc})")
                r.crc
            }
            is Result.Failed -> {
                FileLog.e(TAG, "VD jar refresh failed: ${r.reason}")
                // A usable jar already on disk is still usable; otherwise the
                // caller has to decide whether to abort.
                if (!target.exists() || target.length() == 0L) -1L else -1L
            }
        }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /**
     * Write via a temp file + rename so a crash mid-write cannot leave a
     * truncated jar that still passes an existence check.
     *
     * Falls back to a direct write when rename fails: on FUSE-backed storage
     * (some emulated external volumes) rename can fail, and a complete-but-
     * unrenamed file still beats losing the asset entirely.
     */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File("${target.absolutePath}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "AssetDeployer"
    }
}