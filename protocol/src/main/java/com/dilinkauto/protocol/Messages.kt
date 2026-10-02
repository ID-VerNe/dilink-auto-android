package com.dilinkauto.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serializable protocol messages.
 * Uses simple binary encoding (no protobuf dependency for now — keeps APK small).
 * All multi-byte values are big-endian.
 */

/**
 * Thrown when a message payload is truncated or declares a length that exceeds the
 * remaining buffer. Replaces unchecked BufferUnderflowException so callers can catch
 * malformed frames uniformly without crashing the process.
 */
class ProtocolDecodeException(message: String) : Exception(message)

/**
 * Helpers for length-prefixed, bounds-checked decoding.
 * Every decoder reads a 2-byte unsigned length then [len] bytes; if the buffer does
 * not contain the declared bytes, [ProtocolDecodeException] is thrown instead of the
 * unchecked BufferUnderflowException (which previously escaped the reader coroutine
 * and crashed the app on a single malformed frame).
 */
private fun ByteBuffer.readShortLengthPrefixed(): String {
    if (remaining() < 2) throw ProtocolDecodeException("Truncated length prefix: need 2, have ${remaining()}")
    val len = getShort().toInt() and 0xFFFF
    if (len == 0) return ""
    if (remaining() < len) throw ProtocolDecodeException("Truncated string: need $len, have ${remaining()}")
    val bytes = ByteArray(len)
    get(bytes)
    return String(bytes, Charsets.UTF_8)
}

/** Read [len] bytes as a ByteArray, or throw [ProtocolDecodeException] if unavailable. */
private fun ByteBuffer.readBytes(len: Int): ByteArray {
    if (len == 0) return ByteArray(0)
    if (remaining() < len) throw ProtocolDecodeException("Truncated bytes: need $len, have ${remaining()}")
    val bytes = ByteArray(len)
    get(bytes)
    return bytes
}

private fun ByteBuffer.require(n: Int) {
    if (remaining() < n) throw ProtocolDecodeException("Need $n bytes, have ${remaining()}")
}

// ─── Handshake ───

data class HandshakeRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val supportedFeatures: Int = FEATURE_VIDEO or FEATURE_AUDIO,
    val displayMode: Byte = DISPLAY_MODE_VIRTUAL,
    val screenDpi: Int = 160,
    val appVersionCode: Int,
    val targetFps: Int = 30,
    val appVersionName: String = "",
    /**
     * Car-side user override for the VD DPI. 0 = auto-calibrate via
     * [VideoConfig.calculateOptimalDpi] (the portrait-app-safe cap). Non-zero
     * in [120, 480] bypasses the cap and is used verbatim — fixes "UI too
     * small" for landscape apps at the cost of squeezing portrait-only apps.
     * Trailing field, so older peers that don't send it decode 0 (auto).
     */
    val dpiOverride: Int = 0
) {
    fun encode(): ByteArray {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val verNameBytes = appVersionName.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 2 + nameBytes.size + 4 + 4 + 4 + 1 + 4 + 4 + 4 + 2 + verNameBytes.size + 4)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(protocolVersion)
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        buf.putInt(screenWidth)
        buf.putInt(screenHeight)
        buf.putInt(supportedFeatures)
        buf.put(displayMode)
        buf.putInt(screenDpi)
        buf.putInt(appVersionCode)
        buf.putInt(targetFps)
        buf.putShort(verNameBytes.size.toShort())
        buf.put(verNameBytes)
        buf.putInt(dpiOverride)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): HandshakeRequest {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val version = buf.getInt()
            val deviceName = buf.readShortLengthPrefixed()
            val request = HandshakeRequest(
                protocolVersion = version,
                deviceName = deviceName,
                screenWidth = buf.getInt(),
                screenHeight = buf.getInt(),
                supportedFeatures = buf.getInt(),
                displayMode = if (buf.hasRemaining()) buf.get() else DISPLAY_MODE_VIRTUAL,
                screenDpi = if (buf.remaining() >= 4) buf.getInt() else 160,
                appVersionCode = if (buf.remaining() >= 4) buf.getInt() else 0,
                targetFps = if (buf.remaining() >= 4) buf.getInt() else 30,
                appVersionName = if (buf.remaining() >= 2) buf.readShortLengthPrefixed() else "",
                dpiOverride = if (buf.remaining() >= 4) buf.getInt() else 0
            )
            return request
        }
    }
}

data class HandshakeResponse(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val accepted: Boolean,
    val deviceName: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val virtualDisplayId: Int = -1,
    val adbPort: Int = 5555,
    val vdServerJarPath: String = "",
    val connectionMethod: Byte = CONNECTION_METHOD_USB_ADB,
    val vdDpi: Int = VideoConfig.VIRTUAL_DISPLAY_DPI
) {
    fun encode(): ByteArray {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val jarPathBytes = vdServerJarPath.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 1 + 2 + nameBytes.size + 4 + 4 + 4 + 4 + 2 + jarPathBytes.size + 1 + 4)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(protocolVersion)
        buf.put(if (accepted) 1.toByte() else 0.toByte())
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        buf.putInt(displayWidth)
        buf.putInt(displayHeight)
        buf.putInt(virtualDisplayId)
        buf.putInt(adbPort)
        buf.putShort(jarPathBytes.size.toShort())
        buf.put(jarPathBytes)
        buf.put(connectionMethod)
        buf.putInt(vdDpi)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): HandshakeResponse {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val version = buf.getInt()
            val accepted = buf.get() != 0.toByte()
            val deviceName = buf.readShortLengthPrefixed()
            val dw = buf.getInt()
            val dh = buf.getInt()
            val vdId = if (buf.hasRemaining()) buf.getInt() else -1
            val adbP = if (buf.hasRemaining()) buf.getInt() else 5555
            val jarPath = if (buf.remaining() >= 2) {
                val pathLen = buf.getShort().toInt() and 0xFFFF
                if (pathLen > 0 && buf.remaining() >= pathLen) {
                    String(buf.readBytes(pathLen), Charsets.UTF_8)
                } else ""
            } else ""
            val connMethod = if (buf.hasRemaining()) buf.get() else CONNECTION_METHOD_USB_ADB
            val vdDpi = if (buf.remaining() >= 4) buf.getInt() else VideoConfig.VIRTUAL_DISPLAY_DPI
            return HandshakeResponse(
                protocolVersion = version,
                accepted = accepted,
                deviceName = deviceName,
                displayWidth = dw,
                displayHeight = dh,
                virtualDisplayId = vdId,
                adbPort = adbP,
                vdServerJarPath = jarPath,
                connectionMethod = connMethod,
                vdDpi = vdDpi
            )
        }
    }
}

// ─── Touch Input ───

data class TouchEvent(
    val action: Byte,  // TOUCH_DOWN, TOUCH_MOVE, TOUCH_UP
    val pointerId: Int,
    val x: Float,      // Normalized 0.0-1.0 (relative to display)
    val y: Float,      // Normalized 0.0-1.0
    val pressure: Float,
    val timestamp: Long
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN)
        buf.put(action)
        buf.putInt(pointerId)
        buf.putFloat(x)
        buf.putFloat(y)
        buf.putFloat(pressure)
        buf.putLong(timestamp)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): TouchEvent {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            buf.require(1 + 4 + 4 + 4 + 4 + 8)
            return TouchEvent(
                action = buf.get(),
                pointerId = buf.getInt(),
                x = buf.getFloat(),
                y = buf.getFloat(),
                pressure = buf.getFloat(),
                timestamp = buf.getLong()
            )
        }
    }
}

/** Batched MOVE: all pointers in a single message (reduces syscalls for multi-touch) */
data class TouchMoveBatch(val pointers: List<TouchEvent>) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(1 + pointers.size * 24) // count + N * (id+x+y+p+ts)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(pointers.size.toByte())
        for (p in pointers) {
            buf.putInt(p.pointerId)
            buf.putFloat(p.x)
            buf.putFloat(p.y)
            buf.putFloat(p.pressure)
            buf.putLong(p.timestamp)
        }
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): TouchMoveBatch {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            buf.require(1)
            val count = buf.get().toInt() and 0xFF
            val pointers = (0 until count).map {
                buf.require(4 + 4 + 4 + 4 + 8)
                TouchEvent(
                    action = InputMsg.TOUCH_MOVE,
                    pointerId = buf.getInt(),
                    x = buf.getFloat(),
                    y = buf.getFloat(),
                    pressure = buf.getFloat(),
                    timestamp = buf.getLong()
                )
            }
            return TouchMoveBatch(pointers)
        }
    }
}

// ─── App List ───

data class AppInfo(
    val packageName: String,
    val appName: String,
    val category: AppCategory,
    val iconPng: ByteArray = ByteArray(0),
    val iconHash: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppInfo) return false
        return packageName == other.packageName
    }

    override fun hashCode(): Int = packageName.hashCode()
}

enum class AppCategory(val id: Byte) {
    NAVIGATION(0),
    MUSIC(1),
    COMMUNICATION(2),
    OTHER(3);

    companion object {
        fun fromId(id: Byte): AppCategory = entries.find { it.id == id } ?: OTHER
    }
}

data class AppListMessage(val apps: List<AppInfo>) {
    fun encode(): ByteArray {
        val appBuffers = apps.map { app ->
            val pkgBytes = app.packageName.toByteArray(Charsets.UTF_8)
            val nameBytes = app.appName.toByteArray(Charsets.UTF_8)
            val iconBytes = app.iconPng
            val hashBytes = app.iconHash.toByteArray(Charsets.UTF_8)
            ByteBuffer.allocate(2 + pkgBytes.size + 2 + nameBytes.size + 1 + 4 + iconBytes.size + 2 + hashBytes.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(pkgBytes.size.toShort())
                .apply { put(pkgBytes) }
                .putShort(nameBytes.size.toShort())
                .apply { put(nameBytes) }
                .put(app.category.id)
                .putInt(iconBytes.size)
                .apply { put(iconBytes) }
                .putShort(hashBytes.size.toShort())
                .apply { put(hashBytes) }
                .array()
        }
        val totalSize = 2 + appBuffers.sumOf { it.size }
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(apps.size.toShort())
        appBuffers.forEach { buf.put(it) }
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): AppListMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val count = buf.getShort().toInt() and 0xFFFF
            val apps = (0 until count).map {
                val pkg = buf.readShortLengthPrefixed()
                val name = buf.readShortLengthPrefixed()
                val category = AppCategory.fromId(buf.get())
                val iconSize = if (buf.remaining() >= 4) buf.getInt() else 0
                val iconPng = if (iconSize > 0 && buf.remaining() >= iconSize) buf.readBytes(iconSize) else ByteArray(0)
                val iconHash = if (buf.remaining() >= 2) {
                    val hashLen = buf.getShort().toInt() and 0xFFFF
                    if (hashLen > 0 && buf.remaining() >= hashLen) {
                        String(buf.readBytes(hashLen), Charsets.UTF_8)
                    } else ""
                } else ""
                AppInfo(
                    packageName = pkg,
                    appName = name,
                    category = category,
                    iconPng = iconPng,
                    iconHash = iconHash
                )
            }
            return AppListMessage(apps)
        }
    }
}

// ─── Media ───

data class MediaMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long
) {
    fun encode(): ByteArray {
        val fields = listOf(title, artist, album)
        val fieldBytes = fields.map { it.toByteArray(Charsets.UTF_8) }
        val totalSize = fieldBytes.sumOf { 2 + it.size } + 8
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        fieldBytes.forEach { bytes ->
            buf.putShort(bytes.size.toShort())
            buf.put(bytes)
        }
        buf.putLong(durationMs)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): MediaMetadata {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            return MediaMetadata(
                title = buf.readShortLengthPrefixed(),
                artist = buf.readShortLengthPrefixed(),
                album = buf.readShortLengthPrefixed(),
                durationMs = buf.getLong()
            )
        }
    }
}

data class PlaybackState(
    val state: Byte, // 0=stopped, 1=playing, 2=paused
    val positionMs: Long
) {
    fun encode(): ByteArray {
        return ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .put(state)
            .putLong(positionMs)
            .array()
    }

    companion object {
        const val STOPPED: Byte = 0
        const val PLAYING: Byte = 1
        const val PAUSED: Byte = 2

        fun decode(data: ByteArray): PlaybackState {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            buf.require(1 + 8)
            return PlaybackState(buf.get(), buf.getLong())
        }
    }
}

// ─── Media Actions (car → phone) ───

enum class MediaAction(val id: Byte) {
    PLAY(0), PAUSE(1), NEXT(2), PREVIOUS(3), SEEK(4);

    companion object {
        fun fromId(id: Byte): MediaAction = entries.find { it.id == id } ?: PLAY
    }
}

// ─── Launch App (car → phone) ───

data class LaunchAppMessage(val packageName: String) {
    fun encode(): ByteArray = packageName.toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(data: ByteArray) = LaunchAppMessage(String(data, Charsets.UTF_8))
    }
}

// ─── App Actions ───

/** Phone → Car: an app was uninstalled (payload in DataMsg.APP_UNINSTALLED) */
data class AppUninstalledMessage(val packageName: String) {
    fun encode(): ByteArray = packageName.toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(data: ByteArray) = AppUninstalledMessage(String(data, Charsets.UTF_8))
    }
}

/** Phone → Car: app info data for display on car screen (payload in DataMsg.APP_INFO_DATA) */
data class AppInfoDataMessage(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val installTime: Long,
    val targetSdk: Int
) {
    fun encode(): ByteArray {
        val pkgBytes = packageName.toByteArray(Charsets.UTF_8)
        val nameBytes = appName.toByteArray(Charsets.UTF_8)
        val verBytes = versionName.toByteArray(Charsets.UTF_8)
        val buf = java.nio.ByteBuffer.allocate(
            2 + pkgBytes.size + 2 + nameBytes.size + 2 + verBytes.size + 8 + 8 + 4
        )
        buf.putShort(pkgBytes.size.toShort()); buf.put(pkgBytes)
        buf.putShort(nameBytes.size.toShort()); buf.put(nameBytes)
        buf.putShort(verBytes.size.toShort()); buf.put(verBytes)
        buf.putLong(versionCode)
        buf.putLong(installTime)
        buf.putInt(targetSdk)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): AppInfoDataMessage {
            val buf = java.nio.ByteBuffer.wrap(data)
            return AppInfoDataMessage(
                packageName = buf.readShortLengthPrefixed(),
                appName = buf.readShortLengthPrefixed(),
                versionName = buf.readShortLengthPrefixed(),
                versionCode = buf.long,
                installTime = buf.long,
                targetSdk = buf.int
            )
        }
    }
}

// ─── Constants ───

const val PROTOCOL_VERSION = 1

const val DISPLAY_MODE_MIRROR: Byte = 0
const val DISPLAY_MODE_VIRTUAL: Byte = 1

const val FEATURE_VIDEO = 0x01
const val FEATURE_AUDIO = 0x02
const val FEATURE_MEDIA_CONTROL = 0x08
const val FEATURE_NAVIGATION = 0x10

// Connection methods (handshake response)
const val CONNECTION_METHOD_USB_ADB: Byte = 0
const val CONNECTION_METHOD_WIFI_ADB: Byte = 1
const val CONNECTION_METHOD_SHIZUKU: Byte = 2

