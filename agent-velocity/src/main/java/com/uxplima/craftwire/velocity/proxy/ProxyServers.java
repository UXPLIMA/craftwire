package com.uxplima.craftwire.velocity.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.util.ProxyVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** proxy.info and proxy.servers: what the proxy is and the backend servers behind it, pinged on request. */
final class ProxyServers {
    private final ProxyServer proxy;

    ProxyServers(ProxyServer proxy) {
        this.proxy = proxy;
    }

    CompletableFuture<JsonElement> info(JsonObject p) {
        ProxyVersion v = proxy.getVersion();
        ProxyConfig config = proxy.getConfiguration();
        JsonObject r = new JsonObject();
        r.addProperty("name", v.getName());
        r.addProperty("version", v.getVersion());
        r.addProperty("vendor", v.getVendor());
        r.addProperty("port", proxy.getBoundAddress().getPort());
        r.addProperty("onlineMode", config.isOnlineMode());
        r.addProperty("players", proxy.getPlayerCount());
        JsonArray order = new JsonArray();
        config.getAttemptConnectionOrder().forEach(order::add);
        // The servers a joining player is sent to, in order: what a bot test lands on first.
        r.add("tryOrder", order);
        JsonArray servers = new JsonArray();
        for (RegisteredServer s : proxy.getAllServers()) servers.add(brief(s));
        r.add("servers", servers);
        return CompletableFuture.completedFuture(r);
    }

    CompletableFuture<JsonElement> servers(JsonObject p) {
        boolean ping = Params.optBoolean(p, "ping", true);
        long timeoutMs = Params.optLong(p, "timeoutMs", 3000, 100, 30_000);
        List<CompletableFuture<JsonObject>> all = new ArrayList<>();
        for (RegisteredServer s : proxy.getAllServers()) {
            all.add(ping ? ping(s, timeoutMs) : CompletableFuture.completedFuture(brief(s)));
        }
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApply(done -> {
            JsonArray list = new JsonArray();
            all.forEach(f -> list.add(f.join()));
            JsonObject r = new JsonObject();
            r.addProperty("count", list.size());
            r.add("servers", list);
            return r;
        });
    }

    private static JsonObject brief(RegisteredServer s) {
        ServerInfo info = s.getServerInfo();
        JsonObject o = new JsonObject();
        o.addProperty("name", info.getName());
        o.addProperty("address", info.getAddress().getHostString() + ":" + info.getAddress().getPort());
        o.addProperty("players", s.getPlayersConnected().size());
        return o;
    }

    private static CompletableFuture<JsonObject> ping(RegisteredServer s, long timeoutMs) {
        long started = System.nanoTime();
        return s.ping().orTimeout(timeoutMs, TimeUnit.MILLISECONDS).handle((ping, error) -> {
            JsonObject o = brief(s);
            o.addProperty("online", error == null);
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                o.addProperty("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
                return o;
            }
            o.addProperty("latencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            describe(o, ping);
            return o;
        });
    }

    private static void describe(JsonObject o, ServerPing ping) {
        o.addProperty("version", ping.getVersion().getName());
        o.addProperty("protocol", ping.getVersion().getProtocol());
        ping.getPlayers().ifPresent(players -> {
            o.addProperty("onlinePlayers", players.getOnline());
            o.addProperty("maxPlayers", players.getMax());
        });
        o.addProperty("motd", PlainTextComponentSerializer.plainText().serialize(ping.getDescriptionComponent()));
    }
}
