package com.uxplima.craftwire.bedrock.crypto;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.KeyAgreement;
import org.junit.jupiter.api.Test;

/** ES384 tokens and the ECDH session key of the Bedrock handshake. */
class JwtTest {
    private static JsonObject claims() {
        JsonObject c = new JsonObject();
        c.addProperty("salt", "c2FsdA==");
        c.addProperty("xname", "Bot1");
        return c;
    }

    private static String[] parts(String token) {
        return token.split("\\.");
    }

    @Test
    void aSignedTokenVerifiesAndCarriesItsClaimsAndKey() {
        KeyPair key = Jwt.newKeyPair();
        Jwt.Parsed p = Jwt.verifySelf(Jwt.sign(claims(), key));
        assertEquals("Bot1", p.claims().get("xname").getAsString());
        assertEquals("ES384", p.header().get("alg").getAsString());
        assertEquals(key.getPublic(), p.signedBy());
    }

    /** Bedrock servers expect JOSE signatures (raw R || S, 48 bytes each for P-384), not DER. */
    @Test
    void theSignatureIsRawRAndS() {
        String token = Jwt.sign(claims(), Jwt.newKeyPair());
        assertEquals(96, Base64.getUrlDecoder().decode(parts(token)[2]).length);
    }

    @Test
    void changedClaimsDoNotVerify() {
        String[] p = parts(Jwt.sign(claims(), Jwt.newKeyPair()));
        JsonObject forged = claims();
        forged.addProperty("xname", "Admin");
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(forged.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(SecurityException.class, () -> Jwt.verifySelf(p[0] + "." + body + "." + p[2]));
    }

    @Test
    void aHeaderNamingAnotherKeyDoesNotVerify() {
        String[] signedByA = parts(Jwt.sign(claims(), Jwt.newKeyPair()));
        String[] signedByB = parts(Jwt.sign(claims(), Jwt.newKeyPair()));
        assertThrows(SecurityException.class, () -> Jwt.verifySelf(signedByB[0] + "." + signedByA[1] + "." + signedByA[2]));
    }

    @Test
    void aTokenWithoutASigningKeyIsRefused() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"ES384\"}".getBytes(StandardCharsets.UTF_8));
        String[] p = parts(Jwt.sign(claims(), Jwt.newKeyPair()));
        assertThrows(SecurityException.class, () -> Jwt.verifySelf(header + "." + p[1] + "." + p[2]));
        assertThrows(SecurityException.class, () -> Jwt.verifySelf("only.two"));
    }

    @Test
    void publicKeysSurviveTheirBase64Form() {
        KeyPair key = Jwt.newKeyPair();
        assertEquals(key.getPublic(), Jwt.decodeKey(Jwt.encodeKey(key.getPublic())));
        assertThrows(SecurityException.class, () -> Jwt.decodeKey("bm90IGEga2V5"));
    }

    @Test
    void bothSidesDeriveTheSameSessionKey() throws Exception {
        KeyPair client = Jwt.newKeyPair();
        KeyPair server = Jwt.newKeyPair();
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        byte[] ours = Jwt.sessionKey(client.getPrivate(), server.getPublic(), salt);
        assertArrayEquals(ours, Jwt.sessionKey(server.getPrivate(), client.getPublic(), salt));

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(server.getPrivate());
        ka.doPhase(client.getPublic(), true);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(salt);
        sha.update(ka.generateSecret());
        assertArrayEquals(sha.digest(), ours, "salt comes before the secret");
        assertEquals(32, ours.length);
    }
}
