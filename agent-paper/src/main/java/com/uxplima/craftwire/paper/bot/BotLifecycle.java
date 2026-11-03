package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.paper.Sync;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Leaving the server, exactly once: removing a player twice breaks the server (Folia stops the region). Owning
 * thread of the player only.
 */
final class BotLifecycle {
    private final Bot bot;
    private final BotConnection connection;
    private boolean leaving;

    BotLifecycle(Bot bot, BotConnection connection) {
        this.bot = bot;
        this.connection = connection;
    }

    boolean leaving() {
        return leaving;
    }

    /** Leaves now (quit event, player data saved). Nothing happens when the bot is already leaving (a kick in the same tick). */
    void remove() {
        if (leaving) return;
        leaving = true;
        MinecraftServer.getServer().getPlayerList().remove(bot.player());
        connection.channel.close();
        forgetPlayerFiles(bot.uuid());
    }

    /**
     * Something else closed the connection (a kick): run the normal disconnect path once. On Folia the region does
     * that once the connection is back on its list; the player's scheduler then retires and {@link #retired} ends
     * the bot. True when the bot is gone now.
     */
    boolean leave() {
        leaving = true;
        if (Sync.folia()) {
            FoliaConnections.attach(bot.player(), connection);
            return false;
        }
        connection.handleDisconnection();
        PlayerList list = MinecraftServer.getServer().getPlayerList();
        if (list.getPlayer(bot.uuid()) != null) list.remove(bot.player());
        forgetPlayerFiles(bot.uuid());
        return true;
    }

    void retired() {
        leaving = true;
        forgetPlayerFiles(bot.uuid());
    }

    /** Bots are throwaway players: drop the data, stats and advancements the server saved for them on leaving. */
    void forgetPlayerFiles(UUID uuid) {
        MinecraftServer server = MinecraftServer.getServer();
        for (LevelResource dir : new LevelResource[] {LevelResource.PLAYER_DATA_DIR, LevelResource.PLAYER_OLD_DATA_DIR,
                LevelResource.PLAYER_STATS_DIR, LevelResource.PLAYER_ADVANCEMENTS_DIR}) {
            Path folder = server.getWorldPath(dir);
            for (String suffix : new String[] {".dat", ".dat_old", ".json"}) {
                try {
                    Files.deleteIfExists(folder.resolve(uuid + suffix));
                } catch (IOException ignored) {
                    // a leftover file is harmless; the next removal tries again
                }
            }
        }
    }
}
