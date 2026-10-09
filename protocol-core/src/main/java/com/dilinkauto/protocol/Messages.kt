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

/**
 * Lenient variant of [readShortLengthPrefixed] for *optional trailing* string
 * fields: returns "" when the 2-byte length (or the declared bytes) are not
 * present instead of throwing. Older peers may omit these fields entirely —
 * strict parsing would turn that into a hard decode failure.
 * (Previously hand-written at each call site with slightly different bounds
 * checks; both sites now share this helper.)
 */
private fun ByteBuffer.readShortLengthPrefixedOrEmpty(): String {
    if (remaining() < 2) return ""
    val len = getShort().toInt() and 0xFFFF
    if (len == 0 || remaining() < len) return ""
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

/**
 * Android package name / component shape check shared by the peers.
 *
 * Payloads that reach this validator end up interpolated into `am start` /
 * `pm uninstall` shell lines on the phone (shell UID), so the whitelist must
 * exclude every shell metacharacter — `;` `$` backtick quote whitespace `|`
 * `&` `>` `<` `(` `)` — not merely "looks like a package". A component is
 * `pkg/.Activity` (the class half may contain `$` for nested classes).
 *
 * Public (not internal) because the shell-quoting call sites live in other
 * modules — vd-server runs as shell UID and is exactly where these must hold.
 */
val PACKAGE_NAME_REGEX = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")
val COMPONENT_REGEX = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*/[A-Za-z0-9_.$]+$")

/** Throws [ProtocolDecodeException] unless [name] is a syntactically safe package name. */
fun requirePackageName(name: String): String {
    if (name.length > 255 || !PACKAGE_NAME_REGEX.matches(name)) {
        throw ProtocolDecodeException("Illegal package name: ${name.length} chars")
    }
    return name
}

/** Throws [ProtocolDecodeException] unless [component] is a syntactically safe component. */
fun requireComponentName(component: String): String {
    if (component.length > 512 || !COMPONENT_REGEX.matches(component)) {
        throw ProtocolDecodeException("Illegal component name")
    }
    return component
}

/**
 * Encode-side guard for 2-byte length prefixes: a UTF-8 payload >= 64KB used to
 * wrap negative through [java.nio.ByteBuffer.putShort] and desynchronise the
 * peer's parser. Fail loudly at the encoder instead.
 */
private fun requireUtf8Prefix(value: String) {
    val size = value.toByteArray(Charsets.UTF_8).size
    require(size <= 0xFFFF) { "UTF-8 payload too long for a 16-bit prefix: $size" }
}

private fun ByteBuffer.putUtf8(value: String) {
    requireUtf8Prefix(value)
    val bytes = value.toByteArray(Charsets.UTF_8)
    putShort(bytes.size.toShort())
    put(bytes)
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
    val targetFps: Int = VideoConfig.TARGET_FPS,
    val appVersionName: String = "",
    /**
     * Car-side user override for the VD DPI. 0 = auto-calibrate via
     * [VideoConfig.calculateOptimalDpi] (the portrait-app-safe cap). Non-zero
     * in [120, 480] bypasses the cap and is used verbatim — fixes "UI too
     * small" for landscape apps at the cost of squeezing portrait-only apps.
     * Trailing field, so older peers that don't send it decode 0 (auto).
     */
    val dpiOverride: Int = 0,
    /**
     * Car-side requested video bitrate in bps (e.g. 2_000_000, 3_000_000, 4_000_000).
     * 0 = default (4 Mbps). Trailing field for backward compatibility.
     */
    val bitrate: Int = 0
) {
    fun encode(): ByteArray {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val verNameBytes = appVersionName.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 2 + nameBytes.size + 4 + 4 + 4 + 1 + 4 + 4 + 4 + 2 + verNameBytes.size + 4 + 4)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(protocolVersion)
        buf.putUtf8(deviceName)
        buf.putInt(screenWidth)
        buf.putInt(screenHeight)
        buf.putInt(supportedFeatures)
        buf.put(displayMode)
        buf.putInt(screenDpi)
        buf.putInt(appVersionCode)
        buf.putInt(targetFps)
        buf.putUtf8(appVersionName)
        buf.putInt(dpiOverride)
        buf.putInt(bitrate)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): HandshakeRequest {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            buf.require(4)
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
                // Fallback for legacy peers that omit the field: this fork's
                // tuned target (24), not the generic 30.
                targetFps = if (buf.remaining() >= 4) buf.getInt() else VideoConfig.TARGET_FPS,
                appVersionName = if (buf.remaining() >= 2) buf.readShortLengthPrefixed() else "",
                dpiOverride = if (buf.remaining() >= 4) buf.getInt() else 0,
                bitrate = if (buf.remaining() >= 4) buf.getInt() else 0
            )
            return request
        }

        /**
         * Fluent builder for the two senders of a handshake (desktop and car).
         *
         * The wire format and its defaults already lived here; what drifted was
         * the *construction* of the message at each call site — the desktop and
         * the car each hand-rolled the same 8-field assembly with different
         * sources and, more importantly, different ownership of even-alignment
         * (docs/audit-srp-dry.md DRY-2).
         *
         * The builder does **not** align dimensions. Alignment direction is
         * sender-specific and load-bearing:
         *  - desktop floors to even and never goes below 2 ([DimAlign.evenMin2])
         *  - car widens its nav bar by one pixel so the viewport lands even
         *    ([DimAlign.offsetForEvenRemainder]) — the opposite direction
         *
         * Forcing one rule here would silently break the other, so each caller
         * keeps applying its own and the builder only removes field-name churn.
         */
        fun builder() = Builder()

        class Builder {
            private var deviceName: String = ""
            private var screenWidth: Int = 0
            private var screenHeight: Int = 0
            private var screenDpi: Int = 160
            private var appVersionCode: Int = 0
            private var targetFps: Int = VideoConfig.TARGET_FPS
            private var appVersionName: String = ""
            private var dpiOverride: Int = 0
            private var bitrate: Int = 0

            fun deviceName(value: String) = apply { deviceName = value }
            fun screenSize(width: Int, height: Int) = apply { screenWidth = width; screenHeight = height }
            fun screenDpi(value: Int) = apply { screenDpi = value }
            fun appVersionCode(value: Int) = apply { appVersionCode = value }
            fun targetFps(value: Int) = apply { targetFps = value }
            /** Car only; the desktop config has no version *name*. */
            fun appVersionName(value: String) = apply { appVersionName = value }
            fun dpiOverride(value: Int) = apply { dpiOverride = value }
            fun bitrate(value: Int) = apply { bitrate = value }

            fun build() = HandshakeRequest(
                deviceName = deviceName,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                screenDpi = screenDpi,
                appVersionCode = appVersionCode,
                targetFps = targetFps,
                appVersionName = appVersionName,
                dpiOverride = dpiOverride,
                bitrate = bitrate
            )
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
    val adbPort: Int = Ports.ADB_PORT,
    val vdServerJarPath: String = "",
    val connectionMethod: Byte = CONNECTION_METHOD_USB_ADB,
    val vdDpi: Int = VideoConfig.VIRTUAL_DISPLAY_DPI,
    /**
     * Phone-side recommended VirtualDisplay size (trailing fields, 0 = not
     * provided by older phones). This is [VdDimensions.compute]'s output: the
     * car viewport anti-crop scaled up to the phone's REAL physical long edge
     * so a Chinese-ROM IME, which hardcodes its width to the phone's physical
     * pixel width regardless of density, renders fully inside the VD.
     *
     * Deploy sites without shell access to the phone (the desktop ADB path)
     * MUST use these dims for the VirtualDisplay when > 0 — the desktop's own
     * viewport is the *encoder* size, not the VD size. Sizing the VD from the
     * viewport (1280x720) is what left the IME keyboard 1368px wide on a
     * 1280px canvas (chopped right edge, 2026-10-09). The phone's Shizuku
     * path already deployed with these values directly.
     */
    val vdWidth: Int = 0,
    val vdHeight: Int = 0
) {
    fun encode(): ByteArray {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val jarPathBytes = vdServerJarPath.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 1 + 2 + nameBytes.size + 4 + 4 + 4 + 4 + 2 + jarPathBytes.size + 1 + 4 + 4 + 4)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(protocolVersion)
        buf.put(if (accepted) 1.toByte() else 0.toByte())
        buf.putUtf8(deviceName)
        buf.putInt(displayWidth)
        buf.putInt(displayHeight)
        buf.putInt(virtualDisplayId)
        buf.putInt(adbPort)
        buf.putUtf8(vdServerJarPath)
        buf.put(connectionMethod)
        buf.putInt(vdDpi)
        buf.putInt(vdWidth)
        buf.putInt(vdHeight)
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): HandshakeResponse {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            buf.require(4 + 1)
            val version = buf.getInt()
            val accepted = buf.get() != 0.toByte()
            val deviceName = buf.readShortLengthPrefixed()
            val dw = buf.getInt()
            val dh = buf.getInt()
            val vdId = if (buf.hasRemaining()) buf.getInt() else -1
            val adbP = if (buf.hasRemaining()) buf.getInt() else Ports.ADB_PORT
            val jarPath = buf.readShortLengthPrefixedOrEmpty() // optional trailing field
            val connMethod = if (buf.hasRemaining()) buf.get() else CONNECTION_METHOD_USB_ADB
            val vdDpi = if (buf.remaining() >= 4) buf.getInt() else VideoConfig.VIRTUAL_DISPLAY_DPI
            val vdW = if (buf.remaining() >= 4) buf.getInt() else 0
            val vdH = if (buf.remaining() >= 4) buf.getInt() else 0
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
                vdDpi = vdDpi,
                vdWidth = vdW,
                vdHeight = vdH
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
        /**
         * Unknown ids are corruption, not "OTHER": silently coercing hides a
         * malformed frame and desynchronises the rest of the parse.
         */
        fun fromId(id: Byte): AppCategory = entries.find { it.id == id }
            ?: throw ProtocolDecodeException("Unknown AppCategory id: $id")
    }
}

data class AppListMessage(val apps: List<AppInfo>) {
    fun encode(): ByteArray {
        val appBuffers = apps.map { app ->
            requireUtf8Prefix(app.packageName)
            requireUtf8Prefix(app.appName)
            requireUtf8Prefix(app.iconHash)
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
        require(apps.size <= 0xFFFF) { "AppList count too large for a 16-bit prefix: ${apps.size}" }
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(apps.size.toShort())
        appBuffers.forEach { buf.put(it) }
        return buf.array()
    }

    companion object {
        fun decode(data: ByteArray): AppListMessage {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            buf.require(2)
            val count = buf.getShort().toInt() and 0xFFFF
            val apps = (0 until count).map {
                val pkg = buf.readShortLengthPrefixed()
                val name = buf.readShortLengthPrefixed()
                buf.require(1)
                val category = AppCategory.fromId(buf.get())
                buf.require(4)
                val iconSize = buf.getInt()
                if (iconSize < 0) throw ProtocolDecodeException("Negative icon size: $iconSize")
                // Truncated icon must fail the decode: silently dropping it made
                // the next readShortLengthPrefixedOrEmpty() consume icon bytes as
                // the hash length, caching a garbage iconHash under a real key.
                val iconPng = if (iconSize > 0) buf.readBytes(iconSize) else ByteArray(0)
                val iconHash = buf.readShortLengthPrefixedOrEmpty() // optional trailing field
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
        fields.forEach { requireUtf8Prefix(it) }
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
            val title = buf.readShortLengthPrefixed()
            val artist = buf.readShortLengthPrefixed()
            val album = buf.readShortLengthPrefixed()
            buf.require(8)
            return MediaMetadata(
                title = title,
                artist = artist,
                album = album,
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
        /** Unknown ids are corruption — the old `?: PLAY` started playback on a typo. */
        fun fromId(id: Byte): MediaAction = entries.find { it.id == id }
            ?: throw ProtocolDecodeException("Unknown MediaAction id: $id")
    }
}

// ─── Launch App (car → phone) ───

data class LaunchAppMessage(val packageName: String) {
    fun encode(): ByteArray = packageName.toByteArray(Charsets.UTF_8)

    companion object {
        /**
         * The payload is interpolated into `am start` / `pm uninstall` shell
         * lines running as shell UID on the phone, so it is validated at the
         * decoder boundary — the shell-quoting at the call sites is defence in
         * depth, not the only gate.
         */
        fun decode(data: ByteArray): LaunchAppMessage =
            LaunchAppMessage(requirePackageName(String(data, Charsets.UTF_8)))
    }
}

// ─── App Actions ───

/** Phone → Car: an app was uninstalled (payload in DataMsg.APP_UNINSTALLED) */
data class AppUninstalledMessage(val packageName: String) {
    fun encode(): ByteArray = packageName.toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(data: ByteArray): AppUninstalledMessage =
            AppUninstalledMessage(requirePackageName(String(data, Charsets.UTF_8)))
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
        requireUtf8Prefix(packageName)
        requireUtf8Prefix(appName)
        requireUtf8Prefix(versionName)
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
            val packageName = buf.readShortLengthPrefixed()
            val appName = buf.readShortLengthPrefixed()
            val versionName = buf.readShortLengthPrefixed()
            buf.require(8 + 8 + 4)
            return AppInfoDataMessage(
                packageName = packageName,
                appName = appName,
                versionName = versionName,
                versionCode = buf.long,
                installTime = buf.long,
                targetSdk = buf.int
            )
        }
    }
}
