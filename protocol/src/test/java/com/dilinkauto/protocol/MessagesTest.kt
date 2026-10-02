package com.dilinkauto.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MessagesTest {

    @Test
    fun testHandshakeRequestRoundTrip() {
        val original = HandshakeRequest(
            protocolVersion = PROTOCOL_VERSION,
            deviceName = "TestPhone",
            screenWidth = 1080,
            screenHeight = 2400,
            supportedFeatures = 0x07,
            displayMode = DISPLAY_MODE_VIRTUAL,
            screenDpi = 440,
            appVersionCode = 100,
            targetFps = 60,
            appVersionName = "1.0.0",
            dpiOverride = 213
        )
        val encoded = original.encode()
        val decoded = HandshakeRequest.decode(encoded)

        assertEquals(original.protocolVersion, decoded.protocolVersion)
        assertEquals(original.deviceName, decoded.deviceName)
        assertEquals(original.screenWidth, decoded.screenWidth)
        assertEquals(original.screenHeight, decoded.screenHeight)
        assertEquals(original.supportedFeatures, decoded.supportedFeatures)
        assertEquals(original.displayMode, decoded.displayMode)
        assertEquals(original.screenDpi, decoded.screenDpi)
        assertEquals(original.appVersionCode, decoded.appVersionCode)
        assertEquals(original.targetFps, decoded.targetFps)
        assertEquals(original.appVersionName, decoded.appVersionName)
        assertEquals(original.dpiOverride, decoded.dpiOverride)
    }

    @Test
    fun testHandshakeRequest_DpiOverrideOmittedByOldPeer_DecodesAsZero() {
        // An older car that doesn't send the trailing dpiOverride field must
        // decode as 0 (auto), keeping the wire format backward-compatible.
        // Layout: ver(4) nameLen(2) name(4) sw(4) sh(4) feat(4) mode(1) dpi(4) verCode(4) fps(4) verNameLen(2)
        val buf = ByteBuffer.allocate(4 + 2 + 4 + 4 + 4 + 4 + 1 + 4 + 4 + 4 + 2).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.putShort(4) // nameLen
        buf.put("Test".toByteArray(Charsets.UTF_8))
        buf.putInt(1080) // screenWidth
        buf.putInt(1920) // screenHeight
        buf.putInt(7)    // supportedFeatures
        buf.put(1.toByte()) // displayMode
        buf.putInt(160)  // screenDpi
        buf.putInt(100)  // appVersionCode
        buf.putInt(60)   // targetFps
        buf.putShort(0)  // verNameLen
        // No trailing dpiOverride — simulates an old peer.

        val decoded = HandshakeRequest.decode(buf.array())
        assertEquals(0, decoded.dpiOverride)
    }

    @Test
    fun testHandshakeRequestUnsignedLengthPreventsNegativeArraySize() {
        // Construct a buffer where nameLen has the sign bit set (e.g. 0x8004.toShort())
        // Without `and 0xFFFF`, this would be -32764 and throw NegativeArraySizeException
        val len = 0x8004.toShort()
        val buf = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.putShort(len)
        val data = buf.array()

        try {
            HandshakeRequest.decode(data)
            fail("Expected ProtocolDecodeException due to truncated buffer")
        } catch (e: NegativeArraySizeException) {
            fail("NegativeArraySizeException thrown: signed short was not masked to unsigned!")
        } catch (e: ProtocolDecodeException) {
            // Expected: parsed len as positive 32772, declared length exceeds remaining buffer
        }
    }

    @Test
    fun testHandshakeResponseRoundTrip() {
        val original = HandshakeResponse(
            protocolVersion = PROTOCOL_VERSION,
            accepted = true,
            deviceName = "CarUnit",
            displayWidth = 1920,
            displayHeight = 1080,
            virtualDisplayId = 42,
            adbPort = 5555,
            vdServerJarPath = "/data/local/tmp/vd-server.jar",
            connectionMethod = CONNECTION_METHOD_USB_ADB,
            vdDpi = 160
        )
        val encoded = original.encode()
        val decoded = HandshakeResponse.decode(encoded)

        assertEquals(original.protocolVersion, decoded.protocolVersion)
        assertEquals(original.accepted, decoded.accepted)
        assertEquals(original.deviceName, decoded.deviceName)
        assertEquals(original.displayWidth, decoded.displayWidth)
        assertEquals(original.displayHeight, decoded.displayHeight)
        assertEquals(original.virtualDisplayId, decoded.virtualDisplayId)
        assertEquals(original.adbPort, decoded.adbPort)
        assertEquals(original.vdServerJarPath, decoded.vdServerJarPath)
        assertEquals(original.connectionMethod, decoded.connectionMethod)
        assertEquals(original.vdDpi, decoded.vdDpi)
    }

    @Test
    fun testHandshakeResponseUnsignedLength() {
        val buf = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTOCOL_VERSION)
        buf.put(1.toByte()) // accepted
        buf.putShort(0x8002.toShort()) // nameLen with sign bit
        val data = buf.array()

        try {
            HandshakeResponse.decode(data)
            fail("Expected ProtocolDecodeException")
        } catch (e: NegativeArraySizeException) {
            fail("NegativeArraySizeException thrown: signed short was not masked to unsigned!")
        } catch (e: ProtocolDecodeException) {
            // Expected
        }
    }

    @Test
    fun testAppListMessageRoundTrip() {
        val apps = listOf(
            AppInfo("com.example.app1", "App One", AppCategory.MUSIC, iconPng = byteArrayOf(10, 20), iconHash = "hash1"),
            AppInfo("com.example.app2", "App Two", AppCategory.NAVIGATION, iconPng = byteArrayOf(), iconHash = "")
        )
        val original = AppListMessage(apps)
        val encoded = original.encode()
        val decoded = AppListMessage.decode(encoded)

        assertEquals(2, decoded.apps.size)
        assertEquals("com.example.app1", decoded.apps[0].packageName)
        assertEquals("App One", decoded.apps[0].appName)
        assertEquals(AppCategory.MUSIC, decoded.apps[0].category)
        assertArrayEquals(byteArrayOf(10, 20), decoded.apps[0].iconPng)
        assertEquals("hash1", decoded.apps[0].iconHash)

        assertEquals("com.example.app2", decoded.apps[1].packageName)
        assertEquals("App Two", decoded.apps[1].appName)
        assertEquals(AppCategory.NAVIGATION, decoded.apps[1].category)
    }

    @Test
    fun testMediaMetadataRoundTrip() {
        val original = MediaMetadata(
            title = "Song Title",
            artist = "Artist Name",
            album = "Album Name",
            durationMs = 210000L
        )
        val encoded = original.encode()
        val decoded = MediaMetadata.decode(encoded)

        assertEquals(original.title, decoded.title)
        assertEquals(original.artist, decoded.artist)
        assertEquals(original.album, decoded.album)
        assertEquals(original.durationMs, decoded.durationMs)
    }

    @Test
    fun testAppInfoDataMessageRoundTrip() {
        val original = AppInfoDataMessage(
            packageName = "com.test.app",
            appName = "Test App",
            versionName = "1.0",
            versionCode = 100L,
            installTime = 1234567890L,
            targetSdk = 33
        )
        val encoded = original.encode()
        val decoded = AppInfoDataMessage.decode(encoded)
        assertEquals(original.packageName, decoded.packageName)
        assertEquals(original.appName, decoded.appName)
        assertEquals(original.versionName, decoded.versionName)
        assertEquals(original.versionCode, decoded.versionCode)
        assertEquals(original.installTime, decoded.installTime)
        assertEquals(original.targetSdk, decoded.targetSdk)
    }
}
