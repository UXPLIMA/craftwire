package com.uxplima.craftwire.velocity.proxy;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import java.util.function.BiConsumer;

/**
 * Sends the hub a "proxy" event for each join, server switch, kick from a backend and leave, so wait_for
 * {condition:'server_switch'} and cross-server scenarios can follow players between servers. Velocity calls these
 * on its async event threads.
 */
public final class ProxyEvents {
    private final BiConsumer<String, JsonObject> sink;

    public ProxyEvents(BiConsumer<String, JsonObject> sink) {
        this.sink = sink;
    }

    @Subscribe
    public void onJoin(PostLoginEvent e) {
        send("join", e.getPlayer(), null, null, e.getPlayer().getUsername() + " joined the proxy");
    }

    @Subscribe
    public void onSwitch(ServerConnectedEvent e) {
        String to = e.getServer().getServerInfo().getName();
        String from = e.getPreviousServer().map(s -> s.getServerInfo().getName()).orElse(null);
        send("switch", e.getPlayer(), to, from, e.getPlayer().getUsername() + " " + (from == null ? "" : from + " ") + "-> " + to);
    }

    @Subscribe
    public void onKick(KickedFromServerEvent e) {
        String server = e.getServer().getServerInfo().getName();
        String reason = e.getServerKickReason().map(ProxyPlayers::plain).orElse("");
        JsonObject d = data("kick", e.getPlayer(), server, null, e.getPlayer().getUsername() + " was kicked from " + server + ": " + reason);
        d.addProperty("reason", reason);
        d.addProperty("duringJoin", e.kickedDuringServerConnect());
        sink.accept("proxy", d);
    }

    @Subscribe
    public void onLeave(DisconnectEvent e) {
        String server = e.getPlayer().getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(null);
        send("leave", e.getPlayer(), server, null, e.getPlayer().getUsername() + " left the proxy");
    }

    private void send(String action, Player player, String server, String from, String text) {
        sink.accept("proxy", data(action, player, server, from, text));
    }

    private static JsonObject data(String action, Player player, String server, String from, String text) {
        JsonObject d = new JsonObject();
        d.addProperty("action", action);
        d.addProperty("name", player.getUsername());
        if (server != null) d.addProperty("server", server);
        if (from != null) d.addProperty("from", from);
        d.addProperty("text", text);
        return d;
    }
}
