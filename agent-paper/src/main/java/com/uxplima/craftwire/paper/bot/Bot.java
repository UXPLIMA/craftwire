package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import com.uxplima.craftwire.paper.bot.path.Pathfinder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.Connection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** One fake player. Every method runs on the server thread. */
public final class Bot {
    private static final int RESPAWN_TICKS = 20;

    private final String name;
    private final UUID uuid;
    private final Connection connection;
    private final ServerGamePacketListenerImpl listener;
    private final BotInbox inbox;
    private final BotHud hud;
    private final AtomicInteger pendingTeleport;
    private int sequence;
    private int deadTicks;
    private Walk move;
    private Dig dig;
    private boolean jumpNext;

    private Bot(String name, UUID uuid, Connection connection, ServerGamePacketListenerImpl listener, BotInbox inbox,
            BotHud hud, AtomicInteger pendingTeleport) {
        this.name = name;
        this.uuid = uuid;
        this.connection = connection;
        this.listener = listener;
        this.inbox = inbox;
        this.hud = hud;
        this.pendingTeleport = pendingTeleport;
    }

    /** Joins the server like a client would (join event, tab list, player data), then moves to `at`. */
    static Bot join(String name, Location at) {
        MinecraftServer server = MinecraftServer.getServer();
        ServerLevel level = ((CraftWorld) at.getWorld()).getHandle();
        // Offline-mode UUID: never the same as a real Mojang account's UUID.
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, name);
        BotInbox inbox = new BotInbox(200);
        BotHud hud = new BotHud();
        AtomicInteger pendingTeleport = new AtomicInteger(-1);
        Connection connection = FakeConnection.create(inbox, hud, pendingTeleport);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        Bot bot = new Bot(name, uuid, connection, player.connection, inbox, hud, pendingTeleport);
        bot.bukkit().teleport(at);
        bot.settle();
        return bot;
    }

    public String name() { return name; }
    public UUID uuid() { return uuid; }
    public BotInbox inbox() { return inbox; }
    /** What the bot's HUD would show: sidebar, tab list, boss bars, title, action bar. */
    public JsonObject hud() { return hud.json(name, System.currentTimeMillis()); }
    public ServerGamePacketListenerImpl listener() { return listener; }
    /** The current player entity; a respawn replaces it, the listener follows. */
    public ServerPlayer player() { return listener.player; }
    public Player bukkit() { return player().getBukkitEntity(); }
    public int nextSequence() { return ++sequence; }
    public boolean moving() { return move != null; }
    public boolean digging() { return dig != null; }

    /** One server tick. Returns false once the bot is gone (kicked, banned, disconnected). */
    boolean tick(long now) {
        if (!connection.isConnected()) {
            leave();
            return false;
        }
        ServerPlayer p = player();
        if (p.isDeadOrDying()) {
            if (move != null) finish("died");
            if (dig != null) finishDig("died");
            if (++deadTicks >= RESPAWN_TICKS) {
                deadTicks = 0;
                listener.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            }
            return true;
        }
        deadTicks = 0;
        settle();
        if (move != null) {
            String why = move.tick(this, p, now);
            if (why != null) finish(why);
        }
        if (dig != null) {
            String why = dig.tick(this, p, now);
            if (why != null) finishDig(why);
        }
        if (jumpNext) {
            jumpNext = false;
            p.setJumping(p.onGround());
        } else if (move == null) {
            p.setJumping(false);
        }
        // A real client's movement packets make the server tick the player; a bot has none, so tick it here.
        p.doTick();
        return true;
    }

    /**
     * Sends what a real client sends on its own: the teleport confirmation after every server-side move (join,
     * /tp, respawn) and "world loaded" after joining or respawning. Until then the server ignores the player's
     * block and entity interactions.
     */
    void settle() {
        int teleport = pendingTeleport.getAndSet(-1);
        if (teleport >= 0) ServerCompat.get().acceptTeleport(listener, listener.player, teleport);
        if (!listener.hasClientLoaded()) listener.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    /** Leaves the server now (quit event, player data saved). */
    void remove() {
        if (move != null) finish("removed");
        if (dig != null) finishDig("removed");
        MinecraftServer.getServer().getPlayerList().remove(player());
        connection.channel.close();
        forgetPlayerFiles();
    }

    /** Something else closed the connection (a kick): run the normal disconnect path once. */
    private void leave() {
        if (move != null) finish("removed");
        if (dig != null) finishDig("removed");
        connection.handleDisconnection();
        var list = MinecraftServer.getServer().getPlayerList();
        if (list.getPlayer(uuid) != null) list.remove(player());
        forgetPlayerFiles();
    }

    /** Bots are throwaway players: drop the data, stats and advancements the server saved for them on leaving. */
    private void forgetPlayerFiles() {
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

    /**
     * Walks to the point: along a path the pathfinder found (usePath), else in a straight line. Completes with
     * reached, reason, the position and, for a path, its size.
     */
    CompletableFuture<JsonObject> moveTo(double x, double y, double z, double tolerance, boolean sprint, long timeoutMs,
            boolean usePath, Pathfinder.Options options, boolean partial) {
        if (move != null) finish("replaced");
        if (dig != null) finishDig("replaced");
        Walk walk = new Walk(x, y, z, tolerance, sprint, System.currentTimeMillis() + timeoutMs, usePath, options, partial);
        move = walk;
        String why = walk.plan(player());
        if (sprint) listener.handlePlayerCommand(new ServerboundPlayerCommandPacket(player(), ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        if (why != null) finish(why);
        return walk.done;
    }

    CompletableFuture<JsonObject> breakBlock(Plugin plugin, BlockPos pos, Direction face, long timeoutMs) {
        if (move != null) finish("replaced");
        if (dig != null) finishDig("replaced");
        dig = Dig.start(this, plugin, pos, face, timeoutMs);
        return dig.done;
    }

    /** Jumps on the next tick, if standing on something. */
    void jump() {
        jumpNext = true;
    }

    /** Presses or releases sneak through the input packet, so PlayerToggleSneakEvent fires. */
    void sneak(boolean on) {
        ServerPlayer p = player();
        listener.handlePlayerInput(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, on, p.isSprinting())));
    }

    /** Starts or stops sprinting like a client (PlayerToggleSprintEvent). */
    void sprint(boolean on) {
        listener.handlePlayerCommand(new ServerboundPlayerCommandPacket(player(),
                on ? ServerboundPlayerCommandPacket.Action.START_SPRINTING : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
    }

    /** Drops the held item, or the whole stack (PlayerDropItemEvent). */
    void drop(boolean all) {
        listener.handlePlayerAction(new ServerboundPlayerActionPacket(
                all ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS : ServerboundPlayerActionPacket.Action.DROP_ITEM,
                BlockPos.ZERO, Direction.DOWN));
    }

    /** Swaps the main and off hand (PlayerSwapHandItemsEvent). */
    void swapHands() {
        listener.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
    }

    private void finish(String reason) {
        ServerPlayer p = player();
        p.zza = 0f;
        p.setJumping(false);
        Walk m = move;
        move = null;
        if (m.sprint) {
            p.setSprinting(false);
            listener.handlePlayerCommand(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
        }
        Vec3 pos = p.position();
        JsonObject r = new JsonObject();
        r.addProperty("reached", reason.equals("arrived"));
        r.addProperty("reason", reason);
        r.addProperty("x", BotJson.round(pos.x));
        r.addProperty("y", BotJson.round(pos.y));
        r.addProperty("z", BotJson.round(pos.z));
        r.addProperty("distance", BotJson.round(Steering.horizontal(m.x - pos.x, m.z - pos.z)));
        m.describe(r);
        m.done.complete(r);
    }

    private void finishDig(String reason) {
        Dig d = dig;
        dig = null;
        d.finish(reason);
    }
}
