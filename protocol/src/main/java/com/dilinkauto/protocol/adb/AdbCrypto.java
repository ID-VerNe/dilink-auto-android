package com.dilinkauto.protocol.adb;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/**
 * ADB cryptographic helpers shared by both transports
 * ([TcpAdbConnection] and [UsbAdbConnection]).
 *
 * Centralizes the fixed, spec-determined pieces of the ADB auth handshake
 * and the Android public-key encoding so a change to the digest prefix,
 * the pubkey struct layout, or the fingerprint hash lands in exactly one
 * place. Both transports used to carry byte-identical copies of this logic
 * with two silent divergences (see history):
 *  - Fingerprint hash: TCP used SHA-1, USB used SHA-256. Unified to SHA-1
 *    here, matching AOSP `adb keygen` (the value `adb` itself logs/prints).
 *  - Pubkey suffix: TCP terminated with `" DiLinkAuto@car "` (a trailing
 *    space, no NUL); USB terminated with `" DiLinkAuto@car\0"`. The ADB
 *    protocol requires the public-key string to be NUL-terminated, so the
 *    USB form was correct. Unified to the NUL-terminated form here, which
 *    fixes a latent TcpAdb auth-acceptance bug.
 *
 * Key *storage* policy (file location priority, PEM vs DER, migration) is
 * intentionally NOT shared — the two transports have genuinely different
 * storage rules (USB migrates across sdcard/extFilesDir/filesDir; TCP stores
 * PEM for Dadb compatibility). Those stay in their respective classes.
 */
final class AdbCrypto {

    private static final int ANDROID_PUBKEY_MODULUS_SIZE = 256; // 2048 bits / 8
    private static final int ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4;

    /**
     * SHA-1 DigestInfo ASN.1 prefix (PKCS#1 v1.5). ADB sends the AUTH_TOKEN
     * as a pre-hashed 20-byte SHA-1 digest — this prefix is prepended before
     * signing with NONEwithRSA, which applies PKCS#1 v1.5 padding without
     * re-hashing. Reference: AOSP adb_auth_host.cpp RSA_sign(NID_sha1, ...).
     */
    static final byte[] SHA1_DIGEST_INFO = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private AdbCrypto() {}

    /**
     * Sign an ADB AUTH_TOKEN using the stored private key.
     *
     * @param token the raw 20-byte AUTH_TOKEN (treated as a pre-hashed SHA-1 digest)
     * @return the signed payload for [AdbProtocol.encodeAuth] with AUTH_SIGNATURE
     */
    static byte[] signAuthToken(PrivateKey priv, byte[] token) throws Exception {
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO, 0, digestInfo, 0, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO.length, token.length);
        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(priv);
        sig.update(digestInfo);
        return sig.sign();
    }

    /**
     * Encode an RSA public key in the Android ADB format (ANDROID_PUBKEY).
     * Reference: AOSP libcrypto_utils/android_pubkey.cpp.
     *
     * Wire format: base64(struct) + " " + user@host + "\0"
     */
    static byte[] encodePublicKey(PublicKey publicKey) {
        RSAPublicKey rsaKey = (RSAPublicKey) publicKey;
        BigInteger n = rsaKey.getModulus();
        BigInteger e = rsaKey.getPublicExponent();

        // n0inv = -1/n[0] mod 2^32 (Montgomery reduction parameter)
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));

        // rr = (2^2048)^2 mod n = 2^4096 mod n
        BigInteger rr = BigInteger.ONE
                .shiftLeft(ANDROID_PUBKEY_MODULUS_SIZE * 8)
                .modPow(BigInteger.valueOf(2), n);

        byte[] modulusLE = bigIntToLEPadded(n, ANDROID_PUBKEY_MODULUS_SIZE);
        byte[] rrLE = bigIntToLEPadded(rr, ANDROID_PUBKEY_MODULUS_SIZE);

        int structSize = 4 + 4 + ANDROID_PUBKEY_MODULUS_SIZE + ANDROID_PUBKEY_MODULUS_SIZE + 4;
        ByteBuffer struct = ByteBuffer.allocate(structSize).order(ByteOrder.LITTLE_ENDIAN);
        struct.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS);
        struct.putInt(n0inv.intValue());
        struct.put(modulusLE);
        struct.put(rrLE);
        struct.putInt(e.intValue());

        String base64 = android.util.Base64.encodeToString(struct.array(), android.util.Base64.NO_WRAP);
        // ADB requires the public-key string to be NUL-terminated.
        return (base64 + " DiLinkAuto@car\0").getBytes();
    }

    /** Convert a BigInteger to a fixed-size little-endian byte array, zero-padded. */
    static byte[] bigIntToLEPadded(BigInteger value, int size) {
        byte[] be = value.toByteArray(); // big-endian, may have leading sign byte
        byte[] le = new byte[size]; // zero-filled
        int beStart = (be.length > size && be[0] == 0) ? 1 : 0;
        int copyLen = Math.min(be.length - beStart, size);
        for (int i = 0; i < copyLen; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    /**
     * SHA-1 fingerprint (first 4 bytes, hex) of a DER-encoded public key.
     * Matches the format `adb` itself prints for `adb keygen` diagnostics.
     */
    static String fingerprint(byte[] derKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(derKey);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }
}
