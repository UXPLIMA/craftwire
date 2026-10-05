package com.uxplima.craftwire.fixtures;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin: predictable behaviour for the Craftwire integration tests to observe. */
public final class FixturePlugin extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(Component.text("fixture: now"));
        // Many plugins answer a tick or more later (async lookups, menus); server_command must still see it.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> sender.sendMessage(Component.text("fixture: later")), 2);
        return true;
    }
}
