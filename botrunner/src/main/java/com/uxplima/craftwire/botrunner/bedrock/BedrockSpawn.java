package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.packet.EntityPackets;
import com.uxplima.craftwire.bedrock.packet.ItemPackets;
import com.uxplima.craftwire.bedrock.packet.LoginOut;
import com.uxplima.craftwire.bedrock.packet.PacketIds;
import com.uxplima.craftwire.bedrock.packet.PlayOut;
import com.uxplima.craftwire.bedrock.packet.StartGame;
import java.util.concurrent.CompletableFuture;

/**
 * Getting a Bedrock bot into the world and keeping it there: play status, StartGame (the block palette is built off
 * the loop, then chunks are asked for), respawns, dimension changes and opened containers. Runner loop thread.
 */
final class BedrockSpawn {
    private BedrockSpawn() {}

    /** PlayStatus: 0 login accepted, 3 the player may spawn; the others refuse the login. */
    static void status(BedrockBot b, int status) {
        switch (status) {
            case 0 -> b.send(LoginOut.cacheOff());
            case 3 -> {
                b.send(LoginOut.initialised(b.life.runtimeId()));
                b.send(LoginOut.loadingScreen(2));
                if (b.life.spawn() && !b.joined().isDone()) b.joined().complete(b);
            }
            case 1 -> b.kicked("the server is newer than Bedrock " + PacketIds.GAME_VERSION);
            case 2 -> b.kicked("the server is older than Bedrock " + PacketIds.GAME_VERSION);
            case 7 -> b.kicked("the server is full");
            default -> b.kicked("login refused (play status " + status + ")");
        }
    }

    /** StartGame: the bot's ids, mode and position; chunks are asked for once the block palette is ready. */
    static void startGame(BedrockBot b, StartGame s) {
        b.life.start(s.runtimeId(), s.uniqueId(), s.gameMode(), s.worldGameMode());
        b.serverAuthBreaking(s.serverAuthBlockBreaking());
        b.moves.teleported(s.x(), s.y() - BedrockEntities.EYE, s.z(), s.yaw(), s.pitch());
        CompletableFuture.supplyAsync(() -> b.ctx.blocks().palette(s), b.ctx.blocking())
                .whenComplete((p, err) -> b.ctx.services().loop().execute(() -> {
                    if (err != null) {
                        Throwable t = err.getCause() != null ? err.getCause() : err;
                        if (!b.joined().isDone()) b.joined().completeExceptionally(t);
                        b.leave("the Bedrock block list is unavailable");
                        return;
                    }
                    b.world.enter(s.dimension(), p);
                    b.entities.clear();
                    b.send(LoginOut.requestChunkRadius(b.view()));
                    b.send(LoginOut.loadingScreen(1));
                }));
    }

    static void containerOpened(BedrockBot b, ItemPackets.ContainerOpen o) {
        int state = b.world.state(o.x(), o.y(), o.z());
        String block = state <= 0 || b.world.palette() == null ? null : b.world.palette().blockId(state);
        b.inventory.opened(o, block);
    }

    /** Respawn state 1: the server placed the bot for its new life; the client answers that it is ready. */
    static void respawn(BedrockBot b, EntityPackets.Respawn r) {
        if (r.state() != 1) return;
        b.moves.teleported(r.x(), r.y() - BedrockEntities.EYE, r.z(), b.body.yaw(), b.body.pitch());
        b.send(PlayOut.respawnReady(b.life.runtimeId(), r.x(), r.y(), r.z()));
        b.life.respawned();
    }

    /** The Respawn button: PlayerAction respawn; the server answers with Respawn state 1. */
    static void requestRespawn(BedrockBot b) {
        b.send(PlayOut.playerAction(b.life.runtimeId(), PlayOut.ACTION_RESPAWN, 0, 0, 0, -1));
    }

    static void changeDimension(BedrockBot b, EntityPackets.ChangeDimension c) {
        b.world.enter(c.dimension(), b.world.palette());
        b.entities.clear();
        b.moves.teleported(c.x(), c.y() - BedrockEntities.EYE, c.z(), b.body.yaw(), b.body.pitch());
        b.send(PlayOut.playerAction(b.life.runtimeId(), PlayOut.ACTION_DIMENSION_CHANGE_DONE, 0, 0, 0, 0));
    }
}
