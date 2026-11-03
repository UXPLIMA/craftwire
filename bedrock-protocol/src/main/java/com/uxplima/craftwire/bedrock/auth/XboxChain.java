package com.uxplima.craftwire.bedrock.auth;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bedrock.crypto.Jwt;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;

/**
 * The Minecraft login chain for an Xbox account: POST our public key to multiplayer.minecraft.net/authentication
 * with the account's XSTS token for that relying party ("XBL3.0 x=<userhash>;<token>"). The hub signs the account
 * in and hands the XSTS token over; the key pair never leaves the runner. Blocking; call it off the event loop.
 */
public final class XboxChain {
    public static final String RELYING_PARTY = "https://multiplayer.minecraft.net/";
    private static final URI AUTH = URI.create("https://multiplayer.minecraft.net/authentication");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private XboxChain() {}

    public static String request(String userHash, String xstsToken, KeyPair key, String gameVersion) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("identityPublicKey", Jwt.encodeKey(key.getPublic()));
        HttpRequest req = HttpRequest.newBuilder(AUTH)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("User-Agent", "MCPE/Android")
                .header("Client-Version", gameVersion)
                .header("Authorization", "XBL3.0 x=" + userHash + ";" + xstsToken)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IOException("multiplayer.minecraft.net answered " + res.statusCode());
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
