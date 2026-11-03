package com.uxplima.craftwire.bedrock.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.bedrock.crypto.Jwt;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * The multiplayer token a 1.26 client puts in the login envelope's Token field: a JWT from the Minecraft authorization
 * service, bound to the bot's key (its `cpk` claim). As gophertunnel 0e179fc gets it (minecraft/service): discovery
 * names the auth service and the PlayFab title, PlayFab Client/LoginWithXbox gives a session ticket, session/start
 * turns that into an MCToken, and multiplayer/session/start issues the token for the public key. The hub signs the
 * account in and hands over an XSTS token for {@link #PLAYFAB_RELYING_PARTY}; the key never leaves the runner.
 * Blocking; call it off the event loop.
 */
public final class MultiplayerToken {
    public static final String PLAYFAB_RELYING_PARTY = "http://playfab.xboxlive.com/";
    private static final String DISCOVERY = "https://client.discovery.minecraft-services.net/api/v1.0/discovery/MinecraftPE/builds/";
    /** The user agent gophertunnel's service package sends, as the game's HTTP library does. */
    private static final String USER_AGENT = "libhttpclient/1.0.0.0";
    /** 16 GiB, the memory gophertunnel reports for the device in session/start. */
    private static final String DEVICE_MEMORY = Long.toString(16L << 30);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private MultiplayerToken() {}

    /** The multiplayer token for `key`, from an XSTS token ("XBL3.0 x=userHash;xsts") for the PlayFab relying party. */
    public static String request(String userHash, String xsts, PublicKey key, String gameVersion) throws IOException {
        JsonObject auth = discover(gameVersion);
        String service = trimSlash(string(auth, "serviceUri", "discovery"));
        String title = string(auth, "playFabTitleId", "discovery");
        String ticket = sessionTicket(title, "XBL3.0 x=" + userHash + ";" + xsts);
        String mcToken = sessionStart(service, title, ticket, gameVersion);
        JsonObject body = new JsonObject();
        body.addProperty("publicKey", Jwt.encodeKey(key));
        JsonObject r = result(post(service + "/api/v1.0/multiplayer/session/start", body, mcToken), "result", "multiplayer/session/start");
        return string(r, "signedToken", "multiplayer/session/start");
    }

    /** serviceEnvironments.auth.prod of the discovery document for this game version. */
    private static JsonObject discover(String gameVersion) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(DISCOVERY + gameVersion))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        JsonObject d = result(send(req, "discovery"), "result", "discovery");
        try {
            return d.getAsJsonObject("serviceEnvironments").getAsJsonObject("auth").getAsJsonObject("prod");
        } catch (ClassCastException | NullPointerException e) {
            throw new IOException("discovery has no auth.prod environment", e);
        }
    }

    /** PlayFab Client/LoginWithXbox (go-playfab v2): the session ticket of the account's PlayFab player. */
    private static String sessionTicket(String title, String xboxToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("TitleId", title);
        body.addProperty("CreateAccount", true);
        body.addProperty("XboxToken", xboxToken);
        URI url = URI.create("https://" + title.toLowerCase(Locale.ROOT) + ".playfabapi.com/Client/LoginWithXbox");
        JsonObject data = result(post(url.toString(), body, null), "data", "PlayFab LoginWithXbox");
        return string(data, "SessionTicket", "PlayFab LoginWithXbox");
    }

    /** session/start with the ticket: the "MCToken ..." Authorization header for the authorization service. */
    private static String sessionStart(String service, String title, String ticket, String gameVersion) throws IOException {
        JsonObject device = new JsonObject();
        device.addProperty("applicationType", "MinecraftPE");
        device.add("capabilities", new JsonArray());
        device.addProperty("gameVersion", gameVersion);
        device.addProperty("id", UUID.randomUUID().toString());
        device.addProperty("memory", DEVICE_MEMORY);
        device.addProperty("platform", "Windows10");
        device.addProperty("playFabTitleId", title);
        device.addProperty("storePlatform", "uwp.store");
        device.addProperty("type", "Windows10");
        JsonObject user = new JsonObject();
        user.addProperty("language", "en");
        user.addProperty("languageCode", "en-US");
        user.addProperty("regionCode", "US");
        user.addProperty("token", ticket);
        user.addProperty("tokenType", "PlayFab");
        JsonObject body = new JsonObject();
        body.add("device", device);
        body.add("user", user);
        JsonObject r = result(post(service + "/api/v1.0/session/start", body, null), "result", "session/start");
        return string(r, "authorizationHeader", "session/start");
    }

    private static String post(String url, JsonObject body, String authorization) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (authorization != null) b.header("Authorization", authorization);
        return send(b.build(), URI.create(url).getPath());
    }

    /** The response body; a non-200 answer becomes an IOException naming the step and status, never a token. */
    private static String send(HttpRequest req, String step) throws IOException {
        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IOException(step + " answered HTTP " + res.statusCode());
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static JsonObject result(String body, String field, String step) throws IOException {
        try {
            JsonElement e = JsonParser.parseString(body).getAsJsonObject().get(field);
            if (e == null || !e.isJsonObject()) throw new IOException(step + " answered without " + field);
            return e.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException(step + " answered something that is not JSON", e);
        }
    }

    private static String string(JsonObject o, String field, String step) throws IOException {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || e.getAsString().isEmpty()) throw new IOException(step + " answered without " + field);
        return e.getAsString();
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
