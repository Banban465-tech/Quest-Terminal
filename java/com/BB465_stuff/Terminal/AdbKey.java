package com.BB465_stuff.Terminal;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Android's custom RSA public-key binary format, as used by adb_keys.
 *
 * From AOSP libcrypto_utils/android_pubkey.c:
 *
 *   typedef struct RSAPublicKey {
 *       uint32_t modulus_size_words;  // ANDROID_PUBKEY_MODULUS_SIZE_WORDS == 64
 *       uint32_t n0inv;               // precomputed Montgomery param, -1/n[0] mod 2^32
 *       uint8_t  modulus[256];        // RSA modulus, little-endian
 *       uint8_t  rr[256];             // Montgomery param, R^2 mod n, little-endian
 *       uint32_t exponent;            // 3 or 65537
 *   } RSAPublicKey;                   // == 524 bytes
 *
 * Everything is a little-endian sequence of 32-bit words. android_pubkey_decode()
 * only reads modulus_size_words, modulus and exponent -- n0inv and rr are ignored
 * and recomputed by BoringSSL -- but we compute them properly anyway so the bytes
 * match what a real adb client would send.
 */
public final class AdbKey {

    public static final int MODULUS_SIZE = 256;        // ANDROID_PUBKEY_MODULUS_SIZE
    public static final int MODULUS_SIZE_WORDS = 64;   // ANDROID_PUBKEY_MODULUS_SIZE_WORDS
    public static final int ENCODED_SIZE = 4 + 4 + MODULUS_SIZE * 2 + 4; // 524

    public final RSAPublicKey pub;
    public final PrivateKey priv;

    private AdbKey(RSAPublicKey pub, PrivateKey priv) {
        this.pub = pub;
        this.priv = priv;
    }

    public static AdbKey generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        return new AdbKey((RSAPublicKey) kp.getPublic(), kp.getPrivate());
    }

    public static AdbKey fromPem(File pem) throws Exception {
        String text = new String(Files.readAllBytes(pem.toPath()), StandardCharsets.UTF_8);
        String b64 = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        PrivateKey pk = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
        // Re-derive the public key from the CRT parameters so n and e are
        // guaranteed consistent with what we are about to serialise.
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) pk;
        RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new java.security.spec.RSAPublicKeySpec(
                        crt.getModulus(), crt.getPublicExponent()));
        return new AdbKey(pub, pk);
    }

    /** n0inv = -(n mod 2^32)^-1 mod 2^32, i.e. -1/n[0] with n[0] the lowest word. */
    private static int n0inv(BigInteger n) {
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = n.mod(r32);            // the modulus's least significant word
        return r32.subtract(n0.modInverse(r32)).and(BigInteger.valueOf(0xffffffffL)).intValue();
    }

    /** rr = (2^2048)^2 mod n == 2^4096 mod n */
    private static BigInteger rr(BigInteger n) {
        BigInteger r = BigInteger.ONE.shiftLeft(MODULUS_SIZE * 8);
        return r.multiply(r).mod(n);
    }

    /** Serialise into the 524-byte wire format. */
    public byte[] encode() {
        BigInteger n = pub.getModulus();
        int e = pub.getPublicExponent().intValue();

        byte[] out = new byte[ENCODED_SIZE];
        int p = 0;
        p = put32(out, p, MODULUS_SIZE_WORDS);
        p = put32(out, p, n0inv(n));
        p = putLe(out, p, n.toByteArray(), MODULUS_SIZE);   // modulus, LE, zero-padded
        p = putLe(out, p, rr(n).toByteArray(), MODULUS_SIZE);
        p = put32(out, p, e);
        if (p != ENCODED_SIZE) {
            throw new IllegalStateException("encoded " + p + " bytes, expected " + ENCODED_SIZE);
        }
        return out;
    }

    /** The base64 blob written to adbkey.pub / sent in an AUTH RSAPUBLICKEY packet. */
    public String encodeBase64() {
        return Base64.getEncoder().encodeToString(encode());
    }

    /** Sign an adb 20-byte auth token. adb uses RSA-SHA1 over the raw token. */
    public byte[] signToken(byte[] token) throws Exception {
        java.security.Signature s =
                java.security.Signature.getInstance("SHA1withRSA");
        s.initSign(priv);
        s.update(token);
        return s.sign();
    }

    private static int put32(byte[] b, int off, int v) {
        b[off]     = (byte) (v        );
        b[off + 1] = (byte) (v >>>  8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
        return off + 4;
    }

    /** Little-endian, left-padded with zeros to exactly width bytes. */
    private static int putLe(byte[] b, int off, byte[] be, int width) {
        for (int i = 0; i < width; i++) {
            int src = be.length - 1 - i;
            b[off + i] = src >= 0 ? be[src] : 0;
        }
        return off + width;
    }
}
