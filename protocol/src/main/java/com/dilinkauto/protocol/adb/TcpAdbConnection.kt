package com.dilinkauto.protocol.adb

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.PrivateKey

/**
 * Persistent ADB connection over TCP that reuses a single socket for all commands.
 * Unlike Dadb (which opens a new connection per command), this keeps one socket open
 * and sends all shell commands through it.
 *
 * Shares ADB protocol constants and encoding with AdbProtocol/UsbAdbConnection.
 * The AUTH handshake, Android public-key encoding, and fingerprint hash live in
 * [AdbCrypto] so they cannot silently diverge from [UsbAdbConnection].
 *
 * Key *storage* policy is TCP-specific: keys are written as PEM so they remain
 * readable by Dadb (which the car's dev-mode installer uses). UsbAdbConnection
 * has its own storage rules; the two intentionally do not share that logic.
 */
class TcpAdbConnection(
    private val host: String,
    private val port: Int,
    private val keyDir: File
) : Closeable {
    private var socket: Socket? = null
    private var maxPayload = AdbProtocol.MAX_PAYLOAD
    private var authSignatureSent = false

    val isConnected: Boolean get() = socket?.isConnected == true && !socket!!.isClosed

    fun connect(): Boolean {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), 5000)
            sock.tcpNoDelay = true; sock.keepAlive = true
            sock.soTimeout = 30000
            socket = sock
            val keyPair = getOrCreateKeyPair()
            // Send CNXN
            sock.getOutputStream().write(AdbProtocol.encodeConnect())
            // Handle AUTH + CNXN response
            while (true) {
                val msg = readMessage()
                when (msg.command) {
                    AdbProtocol.A_CNXN -> {
                        maxPayload = negotiateMaxPayload(msg.data)
                        return true
                    }
                    AdbProtocol.A_AUTH -> {
                        handleAuth(msg.arg0, msg.data ?: ByteArray(0), keyPair)
                    }
                    else -> {
                        android.util.Log.w("TcpAdb", "Unexpected cmd: ${msg.command}")
                        close(); return false
                    }
                }
            }
        } catch (e: Exception) {
            close()
            return false
        }
    }

    /** Execute a shell command synchronously. Returns exit code (0 = stream closed, -1 = error). */
    fun shell(command: String): Int {
        val sock = socket ?: return -1
        val localId = nextLocalId()
        var peerRemoteId = 0
        return try {
            synchronized(sock) {
                // OPEN shell stream
                val openBytes = "shell:$command".toByteArray()
                sock.getOutputStream().write(
                    AdbProtocol.encode(AdbProtocol.A_OPEN, localId, 0, openBytes))
                // Read response: OKAY opens stream, WRTE carries data (ack each), CLSE ends.
                var exitCode = -1
                val output = StringBuilder()
                while (true) {
                    val msg = readMessage()
                    when (msg.command) {
                        AdbProtocol.A_OKAY -> {
                            // Stream opened — record the peer's remote id so we can
                            // ack subsequent WRTEs and close cleanly.
                            peerRemoteId = msg.arg0
                        }
                        AdbProtocol.A_WRTE -> {
                            msg.data?.let { output.append(String(it)) }
                            // Acknowledge the write so the device can send more
                            // (ADB flow control — without this the device stalls).
                            writeRaw(AdbProtocol.encode(AdbProtocol.A_OKAY, localId, peerRemoteId, null))
                        }
                        AdbProtocol.A_CLSE -> {
                            // Ack the peer's CLSE with our own CLSE to fully close
                            // the stream on both sides (no half-open leftover).
                            writeRaw(AdbProtocol.encodeClose(localId, peerRemoteId))
                            // 0 means "stream closed cleanly" — NOT "command succeeded".
                            // adb's shell: protocol doesn't surface the real exit code
                            // over this path.
                            exitCode = 0
                            break
                        }
                        else -> break
                    }
                }
                exitCode
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** Start a long-running shell command. Keeps the stream open so the process survives.
     *  Returns the local stream ID if successful, -1 on failure. */
    fun shellBackground(command: String): Int {
        val sock = socket ?: return -1
        val localId = nextLocalId()
        return try {
            synchronized(sock) {
                val openBytes = "shell:$command".toByteArray()
                sock.getOutputStream().write(
                    AdbProtocol.encode(AdbProtocol.A_OPEN, localId, 0, openBytes))
                val msg = readMessage()
                if (msg.command == AdbProtocol.A_OKAY) {
                    localId
                } else {
                    -1
                }
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** Fire-and-forget shell command. Returns true if OPEN+OKAY succeeded. */
    fun shellNoWait(command: String): Boolean {
        return shellBackground(command) >= 0
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    // -- Message I/O --

    data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray?)

    private fun readMessage(): AdbMessage {
        val inp = socket?.getInputStream() ?: throw IllegalStateException("Not connected")
        return readMessageFrom(inp)
    }

    private val nextLocalId = java.util.concurrent.atomic.AtomicInteger(1)
    private fun nextLocalId(): Int = nextLocalId.getAndIncrement()

    // -- AUTH (delegated to AdbCrypto; shared with UsbAdbConnection) --

    private fun handleAuth(type: Int, data: ByteArray, keyPair: KeyPair) {
        // Reply sequencing (sign token first, public key on the second challenge)
        // is shared with UsbAdbConnection via AdbCrypto.buildAuthReply.
        val reply = AdbCrypto.buildAuthReply(type, data, keyPair, authSignatureSent) ?: return
        writeRaw(AdbProtocol.encodeAuth(reply.authType, reply.payload))
        authSignatureSent = true
    }

    private fun writeRaw(data: ByteArray) {
        socket?.getOutputStream()?.write(data)
    }

    // -- Key management (Tcp-specific: PEM storage for Dadb compatibility) --

    companion object {
        // Use same key file name as Dadb and UsbAdbConnection for compatibility
        private const val KEY_FILE = "adbkey"

        /** SHA-1 fingerprint of a DER-encoded public key (delegates to AdbCrypto). */
        fun fingerprint(der: ByteArray): String = AdbCrypto.fingerprint(der)

        /**
         * Negotiate the max payload from a peer's CNXN banner (pure, injectable).
         * Behavior-identical to the previously inline connect() logic, lifted so
         * it can be unit-tested without a socket.
         */
        internal fun negotiateMaxPayload(cnxnData: ByteArray?): Int {
            var m = AdbProtocol.MAX_PAYLOAD
            if (cnxnData != null && cnxnData.size >= 8) {
                val peerMax = ByteBuffer.wrap(cnxnData).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
                m = minOf(peerMax, AdbProtocol.MAX_PAYLOAD)
            }
            return if (m < 1) AdbProtocol.MAX_PAYLOAD else m
        }

        /**
         * Read + parse one ADB message from [inp] (pure over an InputStream, no
         * socket). Fails fast on bad magic / oversized data_len (parseHeader) and
         * on a payload CRC mismatch. Behavior-identical to the instance
         * readMessage(), which now delegates here.
         */
        internal fun readMessageFrom(inp: java.io.InputStream): AdbMessage {
            val header = readFullyFrom(inp, ByteArray(AdbProtocol.HEADER_SIZE))
            val parsed = AdbProtocol.parseHeader(header)
                ?: throw IOException("Malformed ADB header (bad magic or oversized data_len)")
            val dataLen = parsed[3]
            val dataCrc = parsed[4]
            val data = if (dataLen > 0) {
                val d = readFullyFrom(inp, ByteArray(dataLen))
                if (AdbProtocol.checksum(d) != dataCrc) {
                    throw IOException("ADB payload CRC mismatch (len=$dataLen)")
                }
                d
            } else null
            return AdbMessage(parsed[0], parsed[1], parsed[2], data)
        }

        private fun readFullyFrom(inp: java.io.InputStream, buf: ByteArray): ByteArray {
            var off = 0
            while (off < buf.size) {
                val n = inp.read(buf, off, buf.size - off)
                if (n < 0) throw java.io.EOFException("ADB connection closed")
                off += n
            }
            return buf
        }
    }

    private fun getOrCreateKeyPair(): KeyPair {
        keyDir.mkdirs()
        val privFile = File(keyDir, KEY_FILE)
        val pubFile = File(keyDir, KEY_FILE + ".pub")

        if (privFile.exists() && pubFile.exists()) {
            return try {
                // Keypair load/generate primitives live in AdbCrypto (shared with
                // UsbAdbConnection); only the PEM/DER storage format differs here.
                AdbCrypto.loadKeyPair(readKeyBytes(privFile), readKeyBytes(pubFile))
            } catch (e: Exception) {
                generateAndStore(privFile, pubFile)
            }
        }
        return generateAndStore(privFile, pubFile)
    }

    /** Read key bytes, handling both PEM and DER formats */
    private fun readKeyBytes(file: File): ByteArray {
        val text = file.readText()
        return if (text.startsWith("-----BEGIN")) {
            // PEM format (used by Dadb)
            val b64 = text.lines()
                .filter { !it.startsWith("-----") }
                .joinToString("")
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } else {
            file.readBytes()
        }
    }

    private fun generateAndStore(privFile: File, pubFile: File): KeyPair {
        val kp = AdbCrypto.generateKeyPair()
        // Save in PEM format (compatible with both PEM-aware and direct readers).
        // Dadb reads PEM; the AdbCrypto auth path only ever uses the in-memory
        // KeyPair, so the on-disk format is chosen for Dadb compatibility.
        saveKey(privFile, kp.private)
        saveKey(pubFile, kp.public)
        return kp
    }

    private fun saveKey(file: File, key: java.security.Key) {
        val encoded = key.encoded
        val b64 = android.util.Base64.encodeToString(encoded, android.util.Base64.DEFAULT)
        val pem = "-----BEGIN ${if (key is PrivateKey) "RSA PRIVATE" else "PUBLIC"} KEY-----\n" +
                b64 +
                "-----END ${if (key is PrivateKey) "RSA PRIVATE" else "PUBLIC"} KEY-----\n"
        file.writeText(pem)
    }
}
