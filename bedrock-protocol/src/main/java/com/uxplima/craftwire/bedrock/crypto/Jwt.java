package com.uxplima.craftwire.bedrock.crypto;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.KeyAgreement;

/**
 * The ES384 tokens of Bedrock's login and handshake: P-384 keys exchanged as base64 X.509 SubjectPublicKeyInfo
 * (the `x5u` header), signatures in the raw R||S form JWTs use, and the session key SHA-256(salt || ECDH secret).
 */
public final class Jwt {
    /** A token's header and claims; `signedBy` is the public key named in its x5u header, or null. */
    public record Parsed(JsonObject header, JsonObject claims, PublicKey signedBy) {}

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_IN = Base64.getUrlDecoder();

    private Jwt() {}

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp384r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-384 keys are not available in this JVM", e);
        }
    }

    public static String encodeKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodeKey(String base64) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecurityException("not an EC public key", e);
        }
    }

    /** header.claims.signature, signed with `key`, the header naming `x5u` as the signer's public key. */
    public static String sign(JsonObject claims, KeyPair key) {
        JsonObject header = new JsonObject();
        header.addProperty("alg", "ES384");
        header.addProperty("x5u", encodeKey(key.getPublic()));
        String body = part(header.toString()) + "." + part(claims.toString());
        try {
            Signature s = Signature.getInstance("SHA384withECDSAinP1363Format");
            s.initSign(key.getPrivate());
            s.update(body.getBytes(StandardCharsets.US_ASCII));
            return body + "." + URL.encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ES384 signing failed", e);
        }
    }

    /** Parses a token and checks its signature against the key in its own x5u header. */
    public static Parsed verifySelf(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new SecurityException("not a JWT");
        JsonObject header = JsonParser.parseString(new String(URL_IN.decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject claims = JsonParser.parseString(new String(URL_IN.decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
        PublicKey key = header.has("x5u") ? decodeKey(header.get("x5u").getAsString()) : null;
        if (key == null) throw new SecurityException("token names no signing key");
        try {
            Signature s = Signature.getInstance("SHA384withECDSAinP1363Format");
            s.initVerify(key);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(URL_IN.decode(parts[2]))) throw new SecurityException("token signature does not verify");
        } catch (GeneralSecurityException e) {
            throw new SecurityException("token signature does not verify", e);
        }
        return new Parsed(header, claims, key);
    }

    /** SHA-256(salt || ECDH(ourKey, theirKey)): the 32-byte session key both sides derive. */
    public static byte[] sessionKey(PrivateKey ours, PublicKey theirs, byte[] salt) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(ours);
            ka.doPhase(theirs, true);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt);
            sha.update(ka.generateSecret());
            return sha.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ECDH key agreement failed", e);
        }
    }

    private static String part(String json) {
        return URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
