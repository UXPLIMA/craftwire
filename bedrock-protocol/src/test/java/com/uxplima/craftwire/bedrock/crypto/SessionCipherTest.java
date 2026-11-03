package com.uxplima.craftwire.bedrock.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** The session encryption, checked against the wire format written out by hand with plain JCE. */
class SessionCipherTest {
    private static byte[] key() {
        byte[] k = new byte[32];
        new Random(42).nextBytes(k);
        return k;
    }

    /** What a server does: AES-256-CTR from IV = key[0..12] || 00 00 00 02, then SHA-256(counter LE, body, key)[0..8]. */
    @Test
    void sealedBatchesMatchTheProtocolLayout() throws Exception {
        byte[] key = key();
        SessionCipher ours = SessionCipher.encrypting(key);
        byte[] iv = Arrays.copyOf(key, 16);
        iv[12] = 0;
        iv[13] = 0;
        iv[14] = 0;
        iv[15] = 2;
        Cipher theirs = Cipher.getInstance("AES/CTR/NoPadding");
        theirs.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        for (long counter = 0; counter < 3; counter++) {
            byte[] body = ("batch " + counter).getBytes(StandardCharsets.UTF_8);
            byte[] plain = theirs.update(ours.seal(body));
            assertArrayEquals(body, Arrays.copyOf(plain, body.length));
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] le = new byte[8];
            for (int i = 0; i < 8; i++) le[i] = (byte) (counter >>> (8 * i));
            sha.update(le);
            sha.update(body);
            sha.update(key);
            assertArrayEquals(Arrays.copyOf(sha.digest(), 8), Arrays.copyOfRange(plain, body.length, plain.length), "batch " + counter);
        }
    }

    @Test
    void opensWhatTheOtherSideSealed() {
        SessionCipher out = SessionCipher.encrypting(key());
        SessionCipher in = SessionCipher.decrypting(key());
        for (int i = 0; i < 4; i++) {
            byte[] body = new byte[i * 1000];
            new Random(i).nextBytes(body);
            assertArrayEquals(body, in.open(out.seal(body)));
        }
    }

    @Test
    void aFlippedBitFailsTheChecksum() {
        byte[] sealed = SessionCipher.encrypting(key()).seal("hello".getBytes(StandardCharsets.UTF_8));
        sealed[2] ^= 1;
        assertThrows(SecurityException.class, () -> SessionCipher.decrypting(key()).open(sealed));
    }

    @Test
    void aBatchTooShortForItsChecksumIsRefused() {
        assertThrows(SecurityException.class, () -> SessionCipher.decrypting(key()).open(new byte[7]));
    }
}
