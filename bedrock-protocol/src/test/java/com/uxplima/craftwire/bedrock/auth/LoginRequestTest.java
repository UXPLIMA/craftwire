package com.uxplima.craftwire.bedrock.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.bedrock.crypto.Jwt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The Login packet's connection request, read back the way a server reads it. */
class LoginRequestTest {
    private record Request(JsonObject envelope, JsonArray chain, String clientData) {}

    /** Two little-endian length-prefixed strings: the JSON envelope, then the client data token. */
    private static Request parse(byte[] raw) {
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        byte[] envelope = new byte[b.getInt()];
        b.get(envelope);
        byte[] clientData = new byte[b.getInt()];
        b.get(clientData);
        assertFalse(b.hasRemaining(), "nothing after the client data");
        JsonObject env = JsonParser.parseString(new String(envelope, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray chain = JsonParser.parseString(env.get("Certificate").getAsString()).getAsJsonObject().getAsJsonArray("chain");
        return new Request(env, chain, new String(clientData, StandardCharsets.UTF_8));
    }

    private static JsonObject clientData() {
        JsonObject d = new JsonObject();
        d.addProperty("LanguageCode", "tr_TR");
        d.addProperty("DeviceOS", 7);
        return d;
    }

    @Test
    void anOfflineLoginIsSelfSignedAndNamesThePlayer() {
        KeyPair key = Jwt.newKeyPair();
        UUID id = UUID.randomUUID();
        Request r = parse(LoginRequest.offline("Bot1", id, "playfab", key, clientData()));

        assertEquals(2, r.envelope().get("AuthenticationType").getAsInt());
        assertEquals(1, r.chain().size());
        Jwt.Parsed token = Jwt.verifySelf(r.envelope().get("Token").getAsString());
        assertEquals(key.getPublic(), token.signedBy());
        assertEquals("Bot1", token.claims().get("xname").getAsString());
        assertEquals(id.toString(), token.claims().get("leguuid").getAsString());
        assertEquals(Jwt.encodeKey(key.getPublic()), token.claims().get("cpk").getAsString());
        long now = System.currentTimeMillis() / 1000;
        assertTrue(token.claims().get("nbf").getAsLong() < now && now < token.claims().get("exp").getAsLong());

        Jwt.Parsed data = Jwt.verifySelf(r.clientData());
        assertEquals(key.getPublic(), data.signedBy());
        assertEquals("tr_TR", data.claims().get("LanguageCode").getAsString());
    }

    /** Our link goes first and vouches for the key the Mojang chain's first link was issued to. */
    @Test
    void anXboxLoginPutsOurLinkInFrontOfTheMojangChain() {
        KeyPair ours = Jwt.newKeyPair();
        KeyPair mojang = Jwt.newKeyPair();
        JsonObject first = new JsonObject();
        first.addProperty("identityPublicKey", Jwt.encodeKey(ours.getPublic()));
        JsonObject second = new JsonObject();
        second.addProperty("extraData", "xuid");
        JsonArray received = new JsonArray();
        received.add(Jwt.sign(first, mojang));
        received.add(Jwt.sign(second, mojang));
        JsonObject body = new JsonObject();
        body.add("chain", received);

        Request r = parse(LoginRequest.xbox(body.toString(), ours, "mp-token", clientData()));

        assertEquals(0, r.envelope().get("AuthenticationType").getAsInt());
        assertEquals("mp-token", r.envelope().get("Token").getAsString());
        assertEquals(3, r.chain().size());
        Jwt.Parsed link = Jwt.verifySelf(r.chain().get(0).getAsString());
        assertEquals(ours.getPublic(), link.signedBy());
        assertEquals(Jwt.encodeKey(mojang.getPublic()), link.claims().get("identityPublicKey").getAsString());
        assertEquals(received.get(0), r.chain().get(1));
        assertEquals(received.get(1), r.chain().get(2));
        assertEquals(ours.getPublic(), Jwt.verifySelf(r.clientData()).signedBy());
    }
}
