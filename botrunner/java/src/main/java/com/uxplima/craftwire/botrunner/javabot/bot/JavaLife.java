package com.uxplima.craftwire.botrunner.javabot.bot;

import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.path.Cell;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.world.level.GameType;

/**
 * The bot's life as the server tells it: entity id, health and food, game mode, death and respawn, the held slot,
 * and the "player loaded" signal a 26.x client sends once the world around it is there (until then the server keeps
 * the player invulnerable and frozen). A dead bot respawns after a second, like a player clicking Respawn. Runner
 * loop thread.
 */
final class JavaLife {
    private static final int RESPAWN_TICKS = 20;
    /** The vanilla client gives up waiting for its chunk after a few seconds and reports loaded anyway. */
    private static final int LOADED_FALLBACK_TICKS = 60;

    private int entityId = -1;
    private float health = 20;
    private int food = 20;
    private GameType gameMode = GameType.SURVIVAL;
    private boolean dead;
    private int heldSlot;
    private boolean spawned;
    private boolean loadPending;
    private int loadTicks;
    private int deadTicks;

    void login(int id, GameType mode) {
        entityId = id;
        gameMode = mode;
        dead = false;
        loadPending = true;
        loadTicks = 0;
    }

    /** ClientboundRespawnPacket: a new life, or a dimension change. */
    void respawned(GameType mode) {
        gameMode = mode;
        dead = false;
        loadPending = true;
        loadTicks = 0;
    }

    /** The server placed the bot; the first time this completes the join. Returns whether it was the first. */
    boolean positioned() {
        boolean first = !spawned;
        spawned = true;
        return first;
    }

    boolean spawned() {
        return spawned;
    }

    void health(float h, int f) {
        health = h;
        food = f;
        if (h <= 0) dead = true;
    }

    void died() {
        dead = true;
    }

    void gameMode(GameType mode) {
        gameMode = mode;
    }

    GameType mode() {
        return gameMode;
    }

    void heldSlot(int slot) {
        heldSlot = slot;
    }

    int entityId() {
        return entityId;
    }

    double health() {
        return health;
    }

    int food() {
        return food;
    }

    String gameMode() {
        return gameMode.getName();
    }

    boolean dead() {
        return dead;
    }

    int heldSlot() {
        return heldSlot;
    }

    void tick(JavaBot b) {
        if (loadPending) {
            Position p = b.body.position();
            boolean here = b.world.view().cell((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z())).kind() != Cell.Kind.UNKNOWN;
            if (here || ++loadTicks >= LOADED_FALLBACK_TICKS) {
                loadPending = false;
                b.send(new ServerboundPlayerLoadedPacket());
            }
        }
        if (dead && ++deadTicks >= RESPAWN_TICKS) {
            deadTicks = 0;
            b.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        }
    }
}
