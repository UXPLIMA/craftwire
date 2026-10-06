package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

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
    private Move move;

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

    /** One server tick. Returns false once the bot is gone (kicked, banned, disconnected). */
    boolean tick(long now) {
        if (!connection.isConnected()) {
            leave();
            return false;
        }
        ServerPlayer p = player();
        if (p.isDeadOrDying()) {
            if (move != null) finish("died");
            if (++deadTicks >= RESPAWN_TICKS) {
                deadTicks = 0;
                listener.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            }
            return true;
        }
        deadTicks = 0;
        settle();
        if (move != null) steer(p, now);
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
        if (teleport >= 0) listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(teleport));
        if (!listener.hasClientLoaded()) listener.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    /** Leaves the server now (quit event, player data saved). */
    void remove() {
        if (move != null) finish("removed");
        MinecraftServer.getServer().getPlayerList().remove(player());
        connection.channel.close();
        forgetPlayerFiles();
    }

    /** Something else closed the connection (a kick): run the normal disconnect path once. */
    private void leave() {
        if (move != null) finish("removed");
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

    CompletableFuture<JsonObject> moveTo(double x, double y, double z, double tolerance, boolean sprint, long timeoutMs) {
        if (move != null) finish("replaced");
        move = new Move(x, y, z, tolerance, sprint, System.currentTimeMillis() + timeoutMs);
        return move.done;
    }

    private void steer(ServerPlayer p, long now) {
        Vec3 pos = p.position();
        double dx = move.x - pos.x;
        double dz = move.z - pos.z;
        double distance = Steering.horizontal(dx, dz);
        if (distance <= move.tolerance) {
            // Over or under a target that is not at ground level, walking cannot get any closer.
            finish(Math.abs(move.y - pos.y) <= 1.5 ? "arrived" : "height");
        } else if (now >= move.deadline) {
            finish("timeout");
        } else if (move.progress.stuck(distance, now)) {
            finish("stuck");
        } else {
            float yaw = Steering.yaw(dx, dz);
            p.setYRot(yaw);
            p.setYHeadRot(yaw);
            p.zza = 1.0f;
            p.setSprinting(move.sprint);
            p.setJumping(p.horizontalCollision && p.onGround());
        }
    }

    private void finish(String reason) {
        ServerPlayer p = player();
        p.zza = 0f;
        p.setJumping(false);
        p.setSprinting(false);
        Move m = move;
        move = null;
        Vec3 pos = p.position();
        JsonObject r = new JsonObject();
        r.addProperty("reached", reason.equals("arrived"));
        r.addProperty("reason", reason);
        r.addProperty("x", BotJson.round(pos.x));
        r.addProperty("y", BotJson.round(pos.y));
        r.addProperty("z", BotJson.round(pos.z));
        r.addProperty("distance", BotJson.round(Steering.horizontal(m.x - pos.x, m.z - pos.z)));
        m.done.complete(r);
    }

    private static final class Move {
        final double x, y, z, tolerance;
        final boolean sprint;
        final long deadline;
        final Steering.Progress progress = new Steering.Progress(0.3, 2000);
        final CompletableFuture<JsonObject> done = new CompletableFuture<>();

        Move(double x, double y, double z, double tolerance, boolean sprint, long deadline) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.tolerance = tolerance;
            this.sprint = sprint;
            this.deadline = deadline;
        }
    }
}
