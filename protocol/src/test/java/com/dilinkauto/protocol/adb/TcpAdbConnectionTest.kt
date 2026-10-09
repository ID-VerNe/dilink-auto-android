package com.dilinkauto.protocol.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TcpAdbConnection 的纯逻辑单测：CNXN 最大载荷协商 + 报文读取/解析/CRC 校验。
 * 这两段已从实例方法提取为 companion 纯函数（可注入 InputStream），故无需 socket/线程即可覆盖。
 */
class TcpAdbConnectionTest {

    private fun cnxnBanner(version: Int, peerMax: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(version).putInt(peerMax).array()

    // ---- negotiateMaxPayload ----

    @Test
    fun `negotiate takes the peer max when under the ceiling`() {
        assertEquals(0x1000, TcpAdbConnection.negotiateMaxPayload(cnxnBanner(0x01000000, 0x1000)))
    }

    @Test
    fun `negotiate clamps to protocol ceiling for a huge peer max`() {
        assertEquals(AdbProtocol.MAX_PAYLOAD, TcpAdbConnection.negotiateMaxPayload(cnxnBanner(0, 0x7FFFFFFF.toInt())))
    }

    @Test
    fun `negotiate falls back to ceiling when peer reports zero`() {
        // 有敌意/损坏的对端报 0 → 不能停摆，回落 MAX_PAYLOAD
        assertEquals(AdbProtocol.MAX_PAYLOAD, TcpAdbConnection.negotiateMaxPayload(cnxnBanner(0x01000000, 0)))
    }

    @Test
    fun `negotiate falls back when banner is missing or too short`() {
        assertEquals(AdbProtocol.MAX_PAYLOAD, TcpAdbConnection.negotiateMaxPayload(null))
        assertEquals(AdbProtocol.MAX_PAYLOAD, TcpAdbConnection.negotiateMaxPayload(ByteArray(4)))
    }

    // ---- readMessageFrom ----

    @Test
    fun `read message parses header and payload`() {
        val payload = "hello".toByteArray()
        val wire = AdbProtocol.encode(AdbProtocol.A_WRTE, 7, 9, payload)
        val msg = TcpAdbConnection.readMessageFrom(ByteArrayInputStream(wire))
        assertEquals(AdbProtocol.A_WRTE, msg.command)
        assertEquals(7, msg.arg0)
        assertEquals(9, msg.arg1)
        assertArrayEquals(payload, msg.data)
    }

    @Test
    fun `read message with empty payload returns null data`() {
        val wire = AdbProtocol.encodeClose(3, 4)
        val msg = TcpAdbConnection.readMessageFrom(ByteArrayInputStream(wire))
        assertEquals(AdbProtocol.A_CLSE, msg.command)
        assertNull(msg.data)
    }

    @Test
    fun `read message rejects a payload crc mismatch`() {
        // 正确 magic、故意写错 data_crc：必须因 CRC 不符抛 IOException（WiFi 损坏类）
        val payload = ByteArray(4) { 0x7F }
        val header = ByteBuffer.allocate(AdbProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(AdbProtocol.A_WRTE).putInt(1).putInt(2)
            .putInt(payload.size).putInt(999).putInt(AdbProtocol.A_WRTE xor -1).array()
        var threw: IOException? = null
        try {
            TcpAdbConnection.readMessageFrom(ByteArrayInputStream(header + payload))
        } catch (e: IOException) {
            threw = e
        }
        assertTrue("CRC 不符必须抛 IOException", threw != null)
        assertTrue(threw!!.message!!.contains("CRC mismatch"))
    }

    @Test
    fun `read message rejects a bad magic`() {
        val wire = AdbProtocol.encode(AdbProtocol.A_OPEN, 1, 2, null)
        wire[20] = (wire[20].toInt() xor 0xFF).toByte() // corrupt magic
        var threw: IOException? = null
        try {
            TcpAdbConnection.readMessageFrom(ByteArrayInputStream(wire))
        } catch (e: IOException) {
            threw = e
        }
        assertTrue("坏 magic 必须抛 IOException", threw != null)
        assertTrue(threw!!.message!!.contains("Malformed"))
    }
}
