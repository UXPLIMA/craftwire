package com.uxplima.craftwire.fixtures;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

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
        if (args.length == 1 && args[0].equals("buy") && sender instanceof Player player) {
            boolean bought = new FixtureShopEvent(player, 30).callEvent();
            player.sendMessage(Component.text(bought ? "fixture: bought" : "fixture: too expensive"));
            return true;
        }
        if (args.length == 1 && args[0].equals("hud") && sender instanceof Player player) {
            showHud(player);
            return true;
        }
        sender.sendMessage(Component.text("fixture: now"));
        // Many plugins answer a tick or more later (async lookups, menus); server_command must still see it.
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> sender.sendMessage(Component.text("fixture: later")), 2);
        return true;
    }

    /**
     * Builds a HUD the way scoreboard plugins do: a per-player scoreboard whose sidebar lines are blank-ish
     * entries ("§a") carrying their text in team prefix/suffix, hidden numbers, a tab prefix, header/footer,
     * a boss bar, a title and an action bar.
     */
    private static void showHud(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective side = board.registerNewObjective("side", Criteria.DUMMY, Component.text("§eMy Lobby"));
        side.setDisplaySlot(DisplaySlot.SIDEBAR);
        side.numberFormat(NumberFormat.blank());
        String[][] lines = {{"§a", "Coins: ", "42"}, {"§b", "Rank: ", "VIP"}, {"§c", "", ""}};
        for (int i = 0; i < lines.length; i++) {
            Team t = board.registerNewTeam("line" + i);
            t.prefix(Component.text(lines[i][1]));
            t.suffix(Component.text(lines[i][2]));
            t.addEntry(lines[i][0]);
            side.getScore(lines[i][0]).setScore(lines.length - i);
        }
        Objective kills = board.registerNewObjective("kills", Criteria.DUMMY, Component.text("Kills"));
        kills.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        kills.getScore(player.getName()).setScore(3);
        Team vip = board.registerNewTeam("vip");
        vip.prefix(Component.text("[VIP] "));
        vip.addEntry(player.getName());
        player.setScoreboard(board);
        player.sendPlayerListHeaderAndFooter(Component.text("§6Fixture Network"), Component.text("fixture.example"));
        player.showBossBar(BossBar.bossBar(Component.text("Event"), 0.5f, BossBar.Color.RED, BossBar.Overlay.PROGRESS));
        player.showTitle(Title.title(Component.text("Welcome"), Component.text("to the fixture")));
        player.sendActionBar(Component.text("Mana 10"));
    }

    /** Like a command blocker: /cwfixture blocked never runs. */
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!e.getMessage().startsWith("/cwfixture blocked")) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(Component.text("fixture: blocked"));
    }

    /** The shop refuses anything over 20. */
    @EventHandler
    public void onBuy(FixtureShopEvent e) {
        if (e.getPrice() > 20) e.setCancelled(true);
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
