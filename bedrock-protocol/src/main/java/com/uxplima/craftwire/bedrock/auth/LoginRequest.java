package com.uxplima.craftwire.bedrock.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.bedrock.crypto.Jwt;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.UUID;

/**
 * The Login packet's connection request, as the 1.26 client writes it: a JSON envelope holding the certificate
 * chain (as a JSON string), the authentication type and the multiplayer token, then the client data token, each
 * prefixed with its little-endian int32 length. Offline logins (servers with online mode off, Geyser without login
 * checks) carry an empty chain and a self-signed token; Xbox logins carry the chain from multiplayer.minecraft.net
 * with our own link in front.
 */
public final class LoginRequest {
    /** AuthenticationType of a self-signed (offline) login; 0 is a full Xbox login. */
    private static final int SELF_SIGNED = 2;
    private static final String AUDIENCE = "api://auth-minecraft-services/multiplayer";
    private static final long SIX_HOURS = 6 * 3600;
    private static final long OFFLINE_XUID_BASE = 100_000_000_000L;
    private static final long OFFLINE_XUID_SPAN = 900_000_000_000L;

    private LoginRequest() {}

    /** An offline login as `name` with `identity` as the player's UUID. */
    public static byte[] offline(String name, UUID identity, String playFabId, KeyPair key, JsonObject clientData) {
        long now = System.currentTimeMillis() / 1000;
        JsonObject token = new JsonObject();
        token.addProperty("exp", now + SIX_HOURS);
        token.addProperty("nbf", now - SIX_HOURS);
        token.addProperty("aud", AUDIENCE);
        token.addProperty("ipt", "");
        token.addProperty("mid", playFabId);
        token.addProperty("tid", "");
        token.addProperty("cpk", Jwt.encodeKey(key.getPublic()));
        token.addProperty("xid", Long.toString(offlineXuid(name)));
        token.addProperty("xname", name);
        token.addProperty("leguuid", identity.toString());
        JsonArray chain = new JsonArray();
        chain.add("");
        return encode(chain, SELF_SIGNED, Jwt.sign(token, key), Jwt.sign(clientData, key));
    }

    /**
     * The XUID an offline login claims: Floodgate turns it into the Java UUID with Long.parseLong and kicks an empty
     * one ("An internal error happened while handling Floodgate data"). Stable per name, and 12 digits where real
     * XUIDs have 16, so a bot never takes over a real player's UUID or data.
     */
    static long offlineXuid(String name) {
        long hash = UUID.nameUUIDFromBytes(("craftwire-xuid:" + name).getBytes(StandardCharsets.UTF_8)).getLeastSignificantBits();
        return OFFLINE_XUID_BASE + Math.floorMod(hash, OFFLINE_XUID_SPAN);
    }

    /**
     * An Xbox login: `mojangChain` is the body multiplayer.minecraft.net/authentication returned for our public key;
     * our own token goes first, vouching for the key its first link was issued to.
     */
    public static byte[] xbox(String mojangChain, KeyPair key, String multiplayerToken, JsonObject clientData) {
        JsonArray received = JsonParser.parseString(mojangChain).getAsJsonObject().getAsJsonArray("chain");
        String firstHeader = received.get(0).getAsString().split("\\.")[0];
        String headerJson = new String(Base64.getUrlDecoder().decode(firstHeader), StandardCharsets.UTF_8);
        JsonObject header = JsonParser.parseString(headerJson).getAsJsonObject();
        long now = System.currentTimeMillis() / 1000;
        JsonObject link = new JsonObject();
        link.addProperty("exp", now + SIX_HOURS);
        link.addProperty("nbf", now - SIX_HOURS);
        link.addProperty("identityPublicKey", header.get("x5u").getAsString());
        link.addProperty("certificateAuthority", true);
        JsonArray chain = new JsonArray();
        chain.add(Jwt.sign(link, key));
        chain.addAll(received);
        return encode(chain, 0, multiplayerToken == null ? "" : multiplayerToken, Jwt.sign(clientData, key));
    }

    private static byte[] encode(JsonArray chain, int authType, String token, String clientData) {
        JsonObject certificate = new JsonObject();
        certificate.add("chain", chain);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("Certificate", certificate.toString());
        envelope.addProperty("AuthenticationType", authType);
        envelope.addProperty("Token", token);
        byte[] a = envelope.toString().getBytes(StandardCharsets.UTF_8);
        byte[] b = clientData.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[8 + a.length + b.length];
        putIntLE(out, 0, a.length);
        System.arraycopy(a, 0, out, 4, a.length);
        putIntLE(out, 4 + a.length, b.length);
        System.arraycopy(b, 0, out, 8 + a.length, b.length);
        return out;
    }

    private static void putIntLE(byte[] out, int at, int v) {
        out[at] = (byte) v;
        out[at + 1] = (byte) (v >>> 8);
        out[at + 2] = (byte) (v >>> 16);
        out[at + 3] = (byte) (v >>> 24);
    }
}
