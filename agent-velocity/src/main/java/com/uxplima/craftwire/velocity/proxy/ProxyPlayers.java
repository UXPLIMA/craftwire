package com.uxplima.craftwire.velocity.proxy;

import com.uxplima.craftwire.core.AgentError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** proxy.players, proxy.send and proxy.kick: who is on which backend server, and moving or removing them. */
final class ProxyPlayers {
    private static final long SEND_TIMEOUT_MS = 20_000;

    private final ProxyServer proxy;

    ProxyPlayers(ProxyServer proxy) {
        this.proxy = proxy;
    }

    CompletableFuture<JsonElement> players(JsonObject p) {
        Optional<String> server = Params.optString(p, "server");
        int limit = (int) Params.optLong(p, "limit", 200, 1, 5000);
        Collection<Player> all = server.isPresent() ? Params.server(proxy, server.get()).getPlayersConnected() : proxy.getAllPlayers();
        JsonArray list = new JsonArray();
        for (Player player : all) {
            if (list.size() >= limit) break;
            list.add(describe(player));
        }
        JsonObject r = new JsonObject();
        r.addProperty("count", all.size());
        r.add("players", list);
        return CompletableFuture.completedFuture(r);
    }

    CompletableFuture<JsonElement> send(JsonObject p) {
        Player player = Params.player(proxy, Params.string(p, "player"));
        RegisteredServer target = Params.server(proxy, Params.string(p, "server"));
        return player.createConnectionRequest(target).connect()
                .orTimeout(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .thenApply(result -> {
                    JsonObject r = new JsonObject();
                    r.addProperty("player", player.getUsername());
                    r.addProperty("server", target.getServerInfo().getName());
                    r.addProperty("status", result.getStatus().name().toLowerCase(Locale.ROOT));
                    r.addProperty("successful", result.isSuccessful());
                    result.getReasonComponent().ifPresent(reason -> r.addProperty("reason", plain(reason)));
                    if (result.getStatus() == ConnectionRequestBuilder.Status.SERVER_DISCONNECTED) {
                        r.addProperty("hint", "The backend refused the player: read the reason and that server's logs.");
                    }
                    return (JsonElement) r;
                });
    }

    CompletableFuture<JsonElement> kick(JsonObject p) {
        Player player = Params.player(proxy, Params.string(p, "player"));
        String reason = Params.optString(p, "reason").orElse("Kicked by Craftwire");
        if (reason.length() > 256) throw new AgentError("INVALID_PARAMS", "reason is longer than 256 characters", "Shorten the reason.");
        player.disconnect(Component.text(reason));
        JsonObject r = new JsonObject();
        r.addProperty("player", player.getUsername());
        r.addProperty("kicked", true);
        return CompletableFuture.completedFuture(r);
    }

    private static JsonObject describe(Player player) {
        JsonObject o = new JsonObject();
        o.addProperty("name", player.getUsername());
        o.addProperty("uuid", player.getUniqueId().toString());
        player.getCurrentServer().ifPresent(c -> o.addProperty("server", c.getServerInfo().getName()));
        o.addProperty("pingMs", player.getPing());
        o.addProperty("protocol", player.getProtocolVersion().getProtocol());
        o.addProperty("version", player.getProtocolVersion().getMostRecentSupportedVersion());
        o.addProperty("onlineMode", player.isOnlineMode());
        return o;
    }

    static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}
