package com.uxplima.craftwire.botrunner.javabot.session;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import javax.crypto.SecretKey;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;

/**
 * Online-mode login: before answering the server's encryption request the client tells Mojang's session server it is
 * joining (POST session/minecraft/join with the access token, the profile id and the server hash), and the server
 * checks that with hasJoined. Runs on a virtual thread, never on the event loop.
 */
final class SessionServer {
    private static final URI JOIN = URI.create("https://sessionserver.mojang.com/session/minecraft/join");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private SessionServer() {}

    /** Mojang's serverId hash: SHA-1 of server id, shared secret and public key, as a signed hex number. */
    static String serverHash(String serverId, PublicKey key, SecretKey secret) throws CryptException {
        return new BigInteger(Crypt.digestData(serverId, key, secret)).toString(16);
    }

    static void join(String accessToken, String undashedUuid, String serverHash) {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        body.addProperty("selectedProfile", undashedUuid);
        body.addProperty("serverId", serverHash);
        HttpRequest req = HttpRequest.newBuilder(JOIN).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new AgentError("SESSION_REFUSED", "Mojang's session server refused the join (HTTP " + r.statusCode() + ")",
                        "The account's session may have expired: sign in again (client_process {action:'sign_in'}).");
            }
        } catch (IOException e) {
            throw new AgentError("SESSION_UNREACHABLE", "Could not reach Mojang's session server: " + e.getMessage(), "Check the network.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentError("SESSION_UNREACHABLE", "Interrupted while joining", "Spawn again.");
        }
    }
}
