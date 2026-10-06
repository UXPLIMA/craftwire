package com.uxplima.craftwire.paper.bot;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

/** How the server took one bot command: whether plugins let PlayerCommandPreprocessEvent through, and its final text. */
final class CommandWatch implements Listener {
    private final UUID player;
    private volatile Boolean cancelled;
    private volatile String message;

    private CommandWatch(UUID player) {
        this.player = player;
    }

    static CommandWatch start(Plugin plugin, UUID player) {
        CommandWatch w = new CommandWatch(player);
        Bukkit.getPluginManager().registerEvents(w, plugin);
        return w;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!e.getPlayer().getUniqueId().equals(player) || cancelled != null) return;
        cancelled = e.isCancelled();
        message = e.getMessage();
    }

    /** null until the server processed the command. */
    Boolean cancelled() { return cancelled; }

    String message() { return message; }

    void stop() {
        HandlerList.unregisterAll(this);
    }
}
