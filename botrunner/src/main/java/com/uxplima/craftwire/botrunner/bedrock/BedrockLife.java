package com.uxplima.craftwire.botrunner.bedrock;

/**
 * The bot's life as the server tells it: runtime and unique ids from StartGame, health and hunger, game mode, death
 * and respawn, the held hotbar slot. A dead bot asks to respawn after a second, like a player pressing Respawn.
 * Runner loop thread.
 */
final class BedrockLife {
    static final int RESPAWN_TICKS = 20;
    private static final String[] MODES = {"survival", "creative", "adventure", "survival", "creative", "default", "spectator"};

    private long runtimeId = -1;
    private long uniqueId;
    private float health = 20;
    private int food = 20;
    private int gameMode;
    private int worldGameMode;
    private boolean dead;
    private int deadTicks;
    private int heldSlot;
    private boolean spawned;

    void start(long runtime, long unique, int mode, int worldMode) {
        runtimeId = runtime;
        uniqueId = unique;
        gameMode = mode;
        worldGameMode = worldMode;
    }

    long runtimeId() {
        return runtimeId;
    }

    long uniqueId() {
        return uniqueId;
    }

    /** PlayStatus 3 (player spawn); returns whether it was the first. */
    boolean spawn() {
        boolean first = !spawned;
        spawned = true;
        return first;
    }

    boolean spawned() {
        return spawned;
    }

    void health(float h) {
        if (Float.isNaN(h)) return;
        health = h;
        if (h <= 0 && !dead) {
            dead = true;
            deadTicks = 0;
        }
    }

    void food(float f) {
        if (!Float.isNaN(f)) food = Math.round(f);
    }

    void gameMode(int mode) {
        gameMode = mode;
    }

    /** Respawn state 1 (ready): the bot is alive at the server's spawn point. */
    void respawned() {
        dead = false;
        health = 20;
    }

    /** True on the tick a dead bot should ask to respawn. */
    boolean respawnDue() {
        return dead && ++deadTicks == RESPAWN_TICKS;
    }

    boolean dead() {
        return dead;
    }

    float health() {
        return health;
    }

    int food() {
        return food;
    }

    boolean creative() {
        int m = effectiveMode();
        return m == 1 || m == 4;
    }

    /** Game mode 5 means "the world's default". */
    private int effectiveMode() {
        return gameMode == 5 ? worldGameMode : gameMode;
    }

    String gameMode() {
        int m = effectiveMode();
        return m >= 0 && m < MODES.length ? MODES[m] : "survival";
    }

    int heldSlot() {
        return heldSlot;
    }

    void heldSlot(int slot) {
        heldSlot = slot;
    }
}
