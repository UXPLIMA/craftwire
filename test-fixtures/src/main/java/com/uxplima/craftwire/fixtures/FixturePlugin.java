package com.uxplima.craftwire.fixtures;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only plugin: predictable behaviour for the Craftwire integration tests to observe. */
public final class FixturePlugin extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equals("menu") && sender instanceof Player player) {
            player.openInventory(new Menu().inventory);
            return true;
        }
        sender.sendMessage(Component.text("fixture: now"));
        // Many plugins answer a tick or more later (async lookups, menus); server_command must still see it.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> sender.sendMessage(Component.text("fixture: later")), 2);
        return true;
    }

    /** Behaves like a typical plugin menu: clicks are cancelled, answered, and the menu closes a tick later. */
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu)) return;
        e.setCancelled(true);
        if (e.getRawSlot() != 4) return;
        Player p = (Player) e.getWhoClicked();
        p.sendMessage(Component.text("fixture: clicked 4"));
        Bukkit.getScheduler().runTask(this, () -> p.closeInventory());
    }

    private static final class Menu implements InventoryHolder {
        final Inventory inventory = Bukkit.createInventory(this, 27, Component.text("Fixture Menu"));

        Menu() {
            inventory.setItem(4, new ItemStack(Material.DIAMOND));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
