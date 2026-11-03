package com.uxplima.craftwire.paper.bot;

import io.papermc.paper.adventure.PaperAdventure;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;

/**
 * Starts a light bot's tab-list copy from what the server shows right now: the same player-info packet a joining
 * client gets, and the current header and footer. Tab entries a plugin sent only as packets (fake rows) appear
 * once the plugin sends them again. Bot thread.
 */
final class BotTab {
    private BotTab() {}

    static void seed(Bot bot) {
        long now = System.currentTimeMillis();
        List<ServerPlayer> players = MinecraftServer.getServer().getPlayerList().getPlayers();
        bot.link().hud.accept(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players, bot.player()), now);
        Player p = bot.bukkit();
        bot.link().hud.accept(new ClientboundTabListPacket(vanilla(p.playerListHeader()), vanilla(p.playerListFooter())), now);
    }

    private static Component vanilla(net.kyori.adventure.text.Component c) {
        return c == null ? Component.empty() : PaperAdventure.asVanilla(c);
    }
}
