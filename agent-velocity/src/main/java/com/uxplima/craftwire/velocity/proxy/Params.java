package com.uxplima.craftwire.velocity.proxy;

import com.uxplima.craftwire.core.AgentError;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;

/** Reads request parameters and looks up the players and servers they name, failing with errors the model can act on. */
final class Params {
    private Params() {}

    static String string(JsonObject p, String key) {
        return optString(p, key).orElseThrow(() -> new AgentError("INVALID_PARAMS", "`" + key + "` is required", "Pass " + key + " as a string."));
    }

    static Optional<String> optString(JsonObject p, String key) {
        JsonElement e = p.get(key);
        if (e == null || e.isJsonNull()) return Optional.empty();
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new AgentError("INVALID_PARAMS", "`" + key + "` must be a string", "Pass " + key + " as a string.");
        }
        return Optional.of(e.getAsString());
    }

    static long optLong(JsonObject p, String key, long fallback, long min, long max) {
        JsonElement e = p.get(key);
        if (e == null || e.isJsonNull()) return fallback;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new AgentError("INVALID_PARAMS", "`" + key + "` must be a number", "Pass " + key + " as a number.");
        }
        return Math.clamp(e.getAsLong(), min, max);
    }

    static boolean optBoolean(JsonObject p, String key, boolean fallback) {
        JsonElement e = p.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
    }

    static Player player(ProxyServer proxy, String name) {
        return proxy.getPlayer(name).orElseThrow(() -> new AgentError("PLAYER_NOT_FOUND", "No player named " + name + " on the proxy",
                "proxy {action:'players'} lists who is connected."));
    }

    static RegisteredServer server(ProxyServer proxy, String name) {
        return proxy.getServer(name).orElseThrow(() -> new AgentError("SERVER_NOT_FOUND", "The proxy has no server named " + name,
                "proxy {action:'servers'} lists the servers in velocity.toml."));
    }
}
