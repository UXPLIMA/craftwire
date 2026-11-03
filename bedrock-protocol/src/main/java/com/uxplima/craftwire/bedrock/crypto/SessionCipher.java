package com.uxplima.craftwire.bedrock.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One direction of the session encryption after ServerToClientHandshake: AES-256-CTR keyed with the shared key,
 * the counter starting from the key's first 12 bytes followed by 0x00000002, and each batch followed by the first
 * 8 bytes of SHA-256(send counter as little-endian long, batch, key), which the receiver checks. One instance per
 * direction; not thread-safe (each direction is used from one event loop).
 */
public final class SessionCipher {
    private static final int CHECKSUM = 8;

    private final byte[] key;
    private final Cipher cipher;
    private long counter;

    private SessionCipher(byte[] key, int mode) {
        this.key = key.clone();
        try {
            byte[] iv = Arrays.copyOf(key, 16);
            iv[12] = 0;
            iv[13] = 0;
            iv[14] = 0;
            iv[15] = 2;
            cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-CTR is not available in this JVM", e);
        }
    }

    public static SessionCipher encrypting(byte[] key) {
        return new SessionCipher(key, Cipher.ENCRYPT_MODE);
    }

    public static SessionCipher decrypting(byte[] key) {
        return new SessionCipher(key, Cipher.DECRYPT_MODE);
    }

    /** The batch body (everything after the 0xfe header) with its checksum appended, encrypted. */
    public byte[] seal(byte[] body) {
        byte[] plain = Arrays.copyOf(body, body.length + CHECKSUM);
        System.arraycopy(checksum(body, 0, body.length), 0, plain, body.length, CHECKSUM);
        return cipher.update(plain);
    }

    /** Decrypts a batch body and checks its checksum; returns the body without it. */
    public byte[] open(byte[] sealed) {
        if (sealed.length < CHECKSUM) throw new SecurityException("encrypted batch is shorter than its checksum");
        byte[] plain = cipher.update(sealed);
        int n = plain.length - CHECKSUM;
        byte[] expected = checksum(plain, 0, n);
        if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(plain, n, plain.length))) {
            throw new SecurityException("batch checksum mismatch (packet " + (counter - 1) + ")");
        }
        return Arrays.copyOf(plain, n);
    }

    private byte[] checksum(byte[] data, int off, int len) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] c = new byte[8];
            long v = counter++;
            for (int i = 0; i < 8; i++) c[i] = (byte) (v >>> (8 * i));
            sha.update(c);
            sha.update(data, off, len);
            sha.update(key);
            return Arrays.copyOf(sha.digest(), CHECKSUM);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
