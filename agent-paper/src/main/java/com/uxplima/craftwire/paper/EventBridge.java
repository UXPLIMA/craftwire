package com.uxplima.craftwire.paper;

import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Forwards player and chat events to the hub, where wait_for and the event buffer see them. */
final class EventBridge implements Listener {
    private final CraftwirePlugin plugin;

    EventBridge(CraftwirePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        plugin.emit("player", player("join", e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.emit("player", player("quit", e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        JsonObject d = new JsonObject();
        d.addProperty("text", PlainTextComponentSerializer.plainText().serialize(e.message()));
        d.addProperty("kind", "chat");
        d.addProperty("sender", e.getPlayer().getName());
        plugin.emit("chat", d);
    }

    private static JsonObject player(String action, Player p) {
        JsonObject d = new JsonObject();
        d.addProperty("action", action);
        d.addProperty("name", p.getName());
        d.addProperty("uuid", p.getUniqueId().toString());
        return d;
    }
}
