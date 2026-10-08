package com.dilinkauto.protocol.adb;

import android.content.Context;
import android.hardware.usb.*;
import android.util.Log;

import java.io.*;
import java.security.*;
import java.security.spec.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADB connection over USB host mode.
 * Implements the ADB protocol using Android's UsbManager/UsbDeviceConnection APIs.
 *
 * Usage:
 *   UsbAdbConnection adb = new UsbAdbConnection(context);
 *   if (adb.connect(usbDevice)) {
 *       String output = adb.shell("ls /data/local/tmp");
 *       adb.push(inputStream, "/data/local/tmp/file.jar");
 *       adb.shellNoWait("CLASSPATH=... app_process ...");
 *       adb.close();
 *   }
 */
public class UsbAdbConnection {
    private static final String TAG = "UsbAdbConnection";

    private final Context context;
    private UsbDeviceConnection connection;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private final AtomicInteger nextLocalId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<Void>> pendingOpens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, BlockingQueue<byte[]>> streamData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, BlockingQueue<byte[]>> streamAcks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> streamPeerIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> streamClosed = new ConcurrentHashMap<>();
    private volatile boolean connected = false;
    private volatile boolean closed = false;
    private Thread readerThread;
    private int maxPayload = AdbProtocol.MAX_PAYLOAD;

    private KeyPair keyPair;
    private String keyDiagInfo;
    private static final String KEY_FILE = "usb_adb_key";

    /** Log callback — route to carLogSend for visibility on the phone */
    private java.util.function.Consumer<String> logSink;

    public void setLogSink(java.util.function.Consumer<String> sink) {
        this.logSink = sink;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (logSink != null) logSink.accept("[UsbAdb] " + msg);
    }

    private void logW(String msg) {
        Log.w(TAG, msg);
        if (logSink != null) logSink.accept("[UsbAdb][W] " + msg);
    }

    private void logE(String msg) {
        Log.e(TAG, msg);
        if (logSink != null) logSink.accept("[UsbAdb][E] " + msg);
    }

    public UsbAdbConnection(Context context) {
        this.context = context;
    }

    /**
     * Find the ADB interface on a USB device (class=255, subclass=66).
     * Returns null if not an ADB device.
     */
    public static UsbInterface findAdbInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == 255 && intf.getInterfaceSubclass() == 66) {
                return intf;
            }
        }
        return null;
    }

    /**
     * Connect to the phone via USB ADB.
     * Handles CNXN + AUTH handshake.
     */
    public boolean connect(UsbDevice device) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbInterface adbInterface = findAdbInterface(device);
        if (adbInterface == null) {
            logE("No ADB interface found on device");
            return false;
        }

        connection = usbManager.openDevice(device);
        if (connection == null) {
            logE("Failed to open USB device — permission denied?");
            return false;
        }

        if (!connection.claimInterface(adbInterface, true)) {
            logE("Failed to claim ADB interface");
            connection.close();
            return false;
        }

        // Find bulk IN/OUT endpoints
        for (int i = 0; i < adbInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = adbInterface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    endpointOut = ep;
                } else {
                    endpointIn = ep;
                }
            }
        }

        if (endpointIn == null || endpointOut == null) {
            logE("Bulk endpoints not found");
            connection.close();
            return false;
        }

        log("USB endpoints found: IN=" + endpointIn.getAddress() + " OUT=" + endpointOut.getAddress());

        // Load or generate RSA key pair for ADB auth
        keyPair = getOrCreateKeyPair();

        // Start reader thread
        readerThread = new Thread(this::readLoop, "UsbAdbReader");
        readerThread.setDaemon(true);
        readerThread.start();

        // Send CONNECT
        sendRaw(AdbProtocol.encodeConnect());
        log("Sent CNXN, waiting for auth...");

        // Wait for connection to complete (auth + connect response)
        // 30s timeout — user may need to approve "Allow USB debugging?" on phone
        long deadline = System.currentTimeMillis() + 30_000;
        while (!connected && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        if (!connected) {
            logE("ADB connection timed out");
            close();
            return false;
        }

        log("USB ADB connected!");
        return true;
    }

    /**
     * Execute a shell command and return the output.
     */
    public String shell(String command) {
        int localId = nextLocalId.getAndIncrement();
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        streamData.put(localId, queue);
        streamClosed.put(localId, false);

        CompletableFuture<Void> openFuture = new CompletableFuture<>();
        pendingOpens.put(localId, openFuture);

        // Open shell stream
        sendRaw(AdbProtocol.encodeOpen(localId, "shell:" + command));

        try {
            openFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logE("shell open failed: " + e.getMessage());
            cleanup(localId);
            return null;
        }

        // Read output until the stream closes or the 30s overall deadline expires.
        // The deadline prevents an indefinite hang when the device disappears
        // without sending A_CLSE (USB unplugged mid-stream): streamClosed stays
        // false and the queue poll would otherwise block forever.
        long deadline = System.currentTimeMillis() + 30_000;
        StringBuilder sb = new StringBuilder();
        while (!Boolean.TRUE.equals(streamClosed.get(localId)) && System.currentTimeMillis() < deadline) {
            try {
                byte[] data = queue.poll(5, TimeUnit.SECONDS);
                if (data != null) {
                    sb.append(new String(data));
                } else if (Boolean.TRUE.equals(streamClosed.get(localId))) {
                    break;
                }
            } catch (InterruptedException e) { break; }
        }
        if (!Boolean.TRUE.equals(streamClosed.get(localId))) {
            logW("shell timed out after 30s: " + command);
        }

        // Drain remaining
        byte[] remaining;
        while ((remaining = queue.poll()) != null) {
            sb.append(new String(remaining));
        }

        cleanup(localId);
        return sb.toString();
    }

    /**
     * Returns diagnostic info about the ADB key (fingerprint, path, whether loaded or generated).
     * Written to a file on the phone for debugging USB auth issues.
     */
    public String keyDiagnostic() {
        return keyDiagInfo != null ? keyDiagInfo : "unknown";
    }

    /**
     * Open a shell stream without waiting for it to finish.
     * Returns the local stream ID for monitoring.
     */
    public int shellNoWait(String command) {
        int localId = nextLocalId.getAndIncrement();
        BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        streamData.put(localId, queue);
        streamClosed.put(localId, false);

        CompletableFuture<Void> openFuture = new CompletableFuture<>();
        pendingOpens.put(localId, openFuture);

        sendRaw(AdbProtocol.encodeOpen(localId, "shell:" + command));

        try {
            openFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logE("shellNoWait open failed: " + e.getMessage());
            cleanup(localId);
            return -1;
        }

        return localId;
    }

    /**
     * Read output from a non-blocking shell stream.
     */
    public String readStream(int localId, long timeoutMs) {
        BlockingQueue<byte[]> queue = streamData.get(localId);
        if (queue == null) return null;
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                byte[] data = queue.poll(100, TimeUnit.MILLISECONDS);
                if (data != null) sb.append(new String(data));
            } catch (InterruptedException e) { break; }
        }
        return sb.toString();
    }

    /**
     * Push a file to the phone via ADB SYNC protocol.
     * Simplified: uses shell cat redirect (works reliably, slightly slower than SYNC).
     */
    public boolean push(InputStream input, String remotePath) {
        try {
            // Read entire file into memory (JAR is ~20KB)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int len;
            while ((len = input.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            byte[] fileData = baos.toByteArray();
            log("Pushing " + fileData.length + " bytes to " + remotePath);

            // Open shell stream for cat redirect
            int localId = nextLocalId.getAndIncrement();
            BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
            BlockingQueue<byte[]> ackQueue = new LinkedBlockingQueue<>();
            streamData.put(localId, queue);
            streamAcks.put(localId, ackQueue);
            streamClosed.put(localId, false);

            CompletableFuture<Void> openFuture = new CompletableFuture<>();
            pendingOpens.put(localId, openFuture);

            sendRaw(AdbProtocol.encodeOpen(localId, "shell:cat > " + remotePath));
            openFuture.get(10, TimeUnit.SECONDS);

            Integer peerId = streamPeerIds.get(localId);
            if (peerId == null) {
                logE("push: no peer ID");
                cleanup(localId);
                return false;
            }

            // Write file data in chunks
            int offset = 0;
            while (offset < fileData.length) {
                int chunkSize = Math.min(maxPayload, fileData.length - offset);
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(fileData, offset, chunk, 0, chunkSize);
                sendRaw(AdbProtocol.encodeWrite(localId, peerId, chunk));
                offset += chunkSize;

                // Wait for OKAY (flow control) on the dedicated ack queue.
                // A null ack before the timeout means the device stopped
                // acknowledging — abort instead of continuing to blast data.
                try {
                    byte[] ack = ackQueue.poll(5, TimeUnit.SECONDS);
                    if (ack == null) {
                        logE("push: ack timeout at offset " + offset + ", aborting");
                        break;
                    }
                } catch (InterruptedException e) { break; }
            }

            // Close the stream (signals EOF to cat)
            sendRaw(AdbProtocol.encodeClose(localId, peerId));
            Thread.sleep(500); // Let shell finish writing
            cleanup(localId);
            log("Push complete: " + remotePath);
            return true;
        } catch (Exception e) {
            logE("push failed: " + e.getMessage());
            return false;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public void close() {
        closed = true;
        connected = false;
        // Unblock any shell()/push() polls stuck on streamClosed.get()==null:
        // mark every stream closed and drop an empty token on each data/ack queue
        // so poll() returns instead of blocking until the 30s deadline.
        for (Integer id : streamClosed.keySet()) {
            streamClosed.put(id, true);
        }
        for (BlockingQueue<byte[]> q : streamData.values()) {
            q.offer(new byte[0]);
        }
        for (BlockingQueue<byte[]> q : streamAcks.values()) {
            q.offer(new byte[0]);
        }
        Thread t = readerThread;
        if (t != null) {
            t.interrupt();
            readerThread = null;
        }
        // Join the reader before closing the USB handle: bulkTransfer can be
        // mid-flight on the reader thread, and closing connection underneath it
        // can crash the native USB stack. 2s is well past the 1s header timeout.
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) {}
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
        streamData.clear();
        streamAcks.clear();
        streamPeerIds.clear();
        streamClosed.clear();
        pendingOpens.clear();
        log("USB ADB closed");
    }

    // ─── Internal ───

    private synchronized void sendRaw(byte[] data) {
        UsbDeviceConnection conn = connection;
        if (conn == null || endpointOut == null) return;
        try {
            int sent = 0;
            while (sent < data.length) {
                int chunkSize = Math.min(16384, data.length - sent);
                int result = conn.bulkTransfer(endpointOut, data, sent, chunkSize, 5000);
                if (result < 0) {
                    logE("bulkTransfer OUT failed: " + result);
                    return;
                }
                sent += result;
            }
        } catch (Exception e) {
            logE("sendRaw error: " + e.getMessage());
        }
    }

    private void readLoop() {
        byte[] headerBuf = new byte[AdbProtocol.HEADER_SIZE];

        try {
        while (!Thread.currentThread().isInterrupted() && !closed) {
            // Capture the connection once per iteration. close() nulls `connection`
            // and joins this thread; without a local snapshot, a TOCTOU window
            // between the null check and the bulkTransfer call can NPE inside
            // the native USB stack when close() races ahead.
            UsbDeviceConnection conn = connection;
            if (conn == null) break;

            // Read 24-byte header — loop to accumulate partial reads.
            // A single bulkTransfer may return fewer bytes than the header;
            // without accumulation the stream desynchronizes permanently.
            int headerRead = 0;
            while (headerRead < AdbProtocol.HEADER_SIZE && !closed) {
                UsbDeviceConnection readConn = connection;
                if (readConn == null) break;
                int n = readConn.bulkTransfer(endpointIn, headerBuf, headerRead,
                        AdbProtocol.HEADER_SIZE - headerRead, 1000);
                if (n < 0) {
                    if (headerRead > 0) {
                        logW("Partial header: got " + headerRead + "/" + AdbProtocol.HEADER_SIZE + ", discarding");
                    }
                    headerRead = 0;
                    break;
                }
                headerRead += n;
            }
            if (headerRead < AdbProtocol.HEADER_SIZE) continue;

            int[] header = AdbProtocol.parseHeader(headerBuf);
            if (header == null) {
                logW("Invalid ADB header");
                continue;
            }

            int command = header[0];
            int arg0 = header[1];
            int arg1 = header[2];
            int dataLen = header[3];

            // Read data payload if present
            byte[] data = null;
            if (dataLen > 0) {
                data = new byte[dataLen];
                int dataRead = 0;
                while (dataRead < dataLen) {
                    if (connection == null) break;
                    int chunk = connection.bulkTransfer(endpointIn, data, dataRead,
                            dataLen - dataRead, 5000);
                    if (chunk < 0) break;
                    dataRead += chunk;
                }
                if (dataRead < dataLen) {
                    logW("Incomplete data: got " + dataRead + "/" + dataLen);
                    continue;
                }
            }

            handleMessage(command, arg0, arg1, data);
        }
        } catch (Exception e) {
            logE("readLoop error: " + e.getMessage());
        }
        log("readLoop exited");
    }

    private void handleMessage(int command, int arg0, int arg1, byte[] data) {
        switch (command) {
            case AdbProtocol.A_CNXN:
                // Connection established — AUTH succeeded, key was accepted
                if (data != null) {
                    String banner = new String(data).trim();
                    log("CNXN received — AUTH SUCCESS: " + banner);
                } else {
                    log("CNXN received — AUTH SUCCESS (no banner)");
                }
                // arg1 = max payload size from device
                if (arg1 > 0) maxPayload = Math.min(arg1, AdbProtocol.MAX_PAYLOAD);
                connected = true;
                log("USB ADB authenticated: maxPayload=" + maxPayload + " keyInfo=" + keyDiagInfo);
                break;

            case AdbProtocol.A_AUTH:
                handleAuth(arg0, data);
                break;

            case AdbProtocol.A_OKAY:
                // Stream opened or ready for more data
                // arg0 = remote id, arg1 = local id
                streamPeerIds.put(arg1, arg0);
                CompletableFuture<Void> future = pendingOpens.remove(arg1);
                if (future != null) {
                    future.complete(null);
                }
                // Route the ack to the dedicated ack queue (flow control for push())
                // — kept separate from streamData so it can't be mistaken for cat stderr.
                BlockingQueue<byte[]> ackQueue = streamAcks.get(arg1);
                if (ackQueue != null) {
                    ackQueue.offer(new byte[0]);
                }
                break;

            case AdbProtocol.A_WRTE:
                // Data from device, arg0 = remote id, arg1 = local id
                BlockingQueue<byte[]> queue = streamData.get(arg1);
                if (queue != null && data != null) {
                    queue.offer(data);
                }
                // Send OKAY to acknowledge
                Integer peerId = streamPeerIds.get(arg1);
                if (peerId != null) {
                    sendRaw(AdbProtocol.encodeOkay(arg1, peerId));
                }
                break;

            case AdbProtocol.A_CLSE:
                // Stream closed by device
                streamClosed.put(arg1, true);
                break;
        }
    }

    private boolean authSignatureSent = false;

    private void handleAuth(int type, byte[] data) {
        log("AUTH received: type=" + type + " (TOKEN=" + AdbProtocol.AUTH_TOKEN + ") dataLen=" + (data != null ? data.length : 0) + " signatureSent=" + authSignatureSent);
        if (type != AdbProtocol.AUTH_TOKEN) {
            logW("Unexpected AUTH type: " + type);
            return;
        }
        if (!authSignatureSent) {
            // First attempt: try signing the token with our stored key.
            // If the phone has "Always allow" checked for this key, CNXN follows immediately.
            // If not, the phone sends another AUTH_TOKEN, and we fall through to RSA public key.
            //
            // ADB sends a raw 20-byte token that must be treated as a PRE-HASHED
            // SHA-1 digest (see [AdbCrypto.signAuthToken] for the rationale).
            try {
                String fp = keyDiagInfo != null ? keyDiagInfo : "unknown";
                log("Signing AUTH_TOKEN with stored key (" + fp + ") using prehashed SHA-1");
                // Reply sequencing shared with TcpAdbConnection (AdbCrypto.buildAuthReply).
                AdbCrypto.AuthReply reply = AdbCrypto.buildAuthReply(type, data, keyPair, false);
                sendRaw(AdbProtocol.encodeAuth(reply.authType, reply.payload));
                authSignatureSent = true;
                log("Sent AUTH_SIGNATURE — waiting for CNXN or second AUTH_TOKEN");
            } catch (Exception e) {
                logE("Failed to sign auth token: " + e.getMessage());
            }
        } else {
            // Signature was rejected — phone didn't recognize our key.
            // Send our public key for user approval (phone shows "Allow USB debugging?" dialog).
            logW("AUTH_SIGNATURE rejected — key not recognized by phone. Sending RSA public key for approval.");
            try {
                AdbCrypto.AuthReply reply = AdbCrypto.buildAuthReply(type, data, keyPair, true);
                // Log key details for debugging key mismatch
                String keyPreview = new String(reply.payload, 0, Math.min(40, reply.payload.length));
                log("AUTH_RSAPUBLICKEY preview: [" + keyPreview + "...] len=" + reply.payload.length
                    + " last4=[" + (reply.payload.length > 4 ? String.format("%02x%02x%02x%02x",
                        reply.payload[reply.payload.length-4], reply.payload[reply.payload.length-3],
                        reply.payload[reply.payload.length-2], reply.payload[reply.payload.length-1]) : "?") + "]");
                sendRaw(AdbProtocol.encodeAuth(reply.authType, reply.payload));
                log("Sent AUTH_RSAPUBLICKEY (pubKeyLen=" + reply.payload.length + ") — user must approve on phone");
            } catch (Exception e) {
                logE("Failed to encode public key: " + e.getMessage());
            }
        }
    }

    private KeyPair getOrCreateKeyPair() {
        // Priority order for key storage (most persistent first):
        // 1. /sdcard/DiLinkAuto/ — survives app reinstalls, updates, and data clears
        // 2. getExternalFilesDir — survives app updates but cleared on full uninstall
        // 3. getFilesDir — cleared on any reinstall (last resort)
        File sdcardDir = new File(android.os.Environment.getExternalStorageDirectory(), "DiLinkAuto");
        log("Key storage check: sdcard=" + sdcardDir.getAbsolutePath() + " exists=" + sdcardDir.exists() + " canWrite=" + sdcardDir.canWrite());
        sdcardDir.mkdirs();

        File extDir = context.getExternalFilesDir(null);
        log("Key storage check: extFilesDir=" + (extDir != null ? extDir.getAbsolutePath() : "null") + " canWrite=" + (extDir != null && extDir.canWrite()));
        log("Key storage check: filesDir=" + context.getFilesDir().getAbsolutePath());

        File keyDir;
        if (sdcardDir.canWrite()) {
            keyDir = sdcardDir;
            log("Using sdcard for key storage: " + keyDir.getAbsolutePath());
        } else {
            keyDir = extDir;
            if (keyDir == null || !keyDir.canWrite()) {
                keyDir = context.getFilesDir();
                logW("Both sdcard and externalFilesDir unavailable, using filesDir");
            } else {
                log("Using externalFilesDir for key storage: " + keyDir.getAbsolutePath());
            }
        }
        keyDir.mkdirs();
        File privFile = new File(keyDir, KEY_FILE);
        File pubFile = new File(keyDir, KEY_FILE + ".pub");

        // Migration: check all other locations for existing keys
        if (!privFile.exists()) {
            log("Key not found at " + privFile.getAbsolutePath() + " — searching other locations");
            File[][] searchDirs = {
                {new File(sdcardDir, KEY_FILE), new File(sdcardDir, KEY_FILE + ".pub")},
                {new File(context.getExternalFilesDir(null) != null ? context.getExternalFilesDir(null) : context.getFilesDir(), KEY_FILE),
                 new File(context.getExternalFilesDir(null) != null ? context.getExternalFilesDir(null) : context.getFilesDir(), KEY_FILE + ".pub")},
                {new File(context.getFilesDir(), KEY_FILE), new File(context.getFilesDir(), KEY_FILE + ".pub")}
            };
            for (File[] pair : searchDirs) {
                if (pair[0].exists() && pair[1].exists() && !pair[0].equals(privFile)) {
                    try {
                        copyFile(pair[0], privFile);
                        copyFile(pair[1], pubFile);
                        log("Migrated ADB key from " + pair[0].getParent() + " to " + keyDir.getAbsolutePath());
                        break;
                    } catch (Exception e) {
                        logW("Key migration failed: " + e.getMessage());
                    }
                }
            }
        }

        log("Key path: " + privFile.getAbsolutePath() + " exists=" + privFile.exists());

        if (privFile.exists() && pubFile.exists()) {
            try {
                byte[] privBytes = readFile(privFile);
                byte[] pubBytes = readFile(pubFile);
                // Keypair load/generate primitives live in AdbCrypto (shared with
                // TcpAdbConnection); only the storage policy above stays USB-specific.
                KeyPair kp = AdbCrypto.loadKeyPair(privBytes, pubBytes);
                String fp = AdbCrypto.fingerprint(kp.getPublic().getEncoded());
                keyDiagInfo = "LOADED path=" + privFile.getAbsolutePath() + " fp=" + fp;
                log("Loaded existing ADB key pair (fingerprint=" + fp + ")");
                return kp;
            } catch (Exception e) {
                logW("Failed to load key pair, generating new: " + e.getMessage());
            }
        }

        try {
            KeyPair kp = AdbCrypto.generateKeyPair();
            writeFile(privFile, kp.getPrivate().getEncoded());
            writeFile(pubFile, kp.getPublic().getEncoded());
            String fp = AdbCrypto.fingerprint(kp.getPublic().getEncoded());
            keyDiagInfo = "GENERATED path=" + privFile.getAbsolutePath() + " fp=" + fp;
            log("Generated NEW ADB key pair (fingerprint=" + fp + ") — phone will ask for auth");
            return kp;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate ADB key pair", e);
        }
    }

    private void cleanup(int localId) {
        streamData.remove(localId);
        streamAcks.remove(localId);
        streamPeerIds.remove(localId);
        streamClosed.remove(localId);
        pendingOpens.remove(localId);
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int n = fis.read(data, offset, data.length - offset);
                if (n == -1) break;
                offset += n;
            }
            return data;
        }
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

}
