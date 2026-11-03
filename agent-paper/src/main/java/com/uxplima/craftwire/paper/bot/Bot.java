package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.behavior.BehaviorSlot;
import com.uxplima.craftwire.bot.behavior.BotControl;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.bot.inbox.Inbox;
import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.bot.model.ClientSettings;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.model.Profile;
import com.uxplima.craftwire.bot.move.WalkOptions;
import com.uxplima.craftwire.bot.move.Walker;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * One fake player inside the server. Every method runs on the thread that owns the player: the main thread on
 * Paper (the global bot loop), the player's region thread on Folia (its entity scheduler).
 */
public final class Bot implements BotControl {
    private static final int RESPAWN_TICKS = 20;
    /** Idle light bots run the player's physics every this many ticks: standing still needs none in between. */
    private static final int IDLE_TICK_EVERY = 10;
    /** How far another player may be for follow to see them: the usual entity tracking range. */
    private static final double SEE_RANGE = 128;

    private final JoinSpec spec;
    private final UUID uuid;
    private final BotConnection connection;
    private final ServerGamePacketListenerImpl listener;
    private final BotLink link;
    private final BotServices svc;
    private final PaperBody body = new PaperBody(this);
    private final BehaviorSlot behavior = new BehaviorSlot();
    private final BotLifecycle life;
    private int sequence;
    private int deadTicks;
    private long ticks;
    private Walker walk;
    private Dig dig;
    private boolean jumpNext;
    private volatile ClientSettings settings;

    /** What one tick found. */
    enum Tick { ALIVE, LEAVING, GONE }

    Bot(JoinSpec spec, UUID uuid, BotConnection connection, ServerGamePacketListenerImpl listener, BotLink link, BotServices svc) {
        this.spec = spec;
        this.uuid = uuid;
        this.connection = connection;
        this.listener = listener;
        this.link = link;
        this.svc = svc;
        this.settings = spec.settings();
        this.life = new BotLifecycle(this, connection);
    }

    @Override
    public String name() { return spec.name(); }
    @Override
    public BotKind kind() { return BotKind.PLUGIN; }
    @Override
    public Inbox inbox() { return link.inbox; }
    public UUID uuid() { return uuid; }
    public String group() { return spec.group(); }
    public Profile profile() { return spec.profile(); }
    public ClientSettings settings() { return settings; }
    BotLink link() { return link; }
    BotServices services() { return svc; }
    BehaviorSlot behavior() { return behavior; }
    public ServerGamePacketListenerImpl listener() { return listener; }
    /** The current player entity; a respawn replaces it, the listener follows. */
    public ServerPlayer player() { return listener.player; }
    public Player bukkit() { return player().getBukkitEntity(); }
    public int nextSequence() { return ++sequence; }
    @Override
    public boolean moving() { return walk != null; }
    public boolean digging() { return dig != null; }
    boolean leaving() { return life.leaving(); }

    /** What the bot's HUD would show; the first read of a light bot starts copying the tab list. */
    public JsonObject hud() {
        if (link.watchTab()) BotTab.seed(this);
        return link.hud.json(name(), System.currentTimeMillis());
    }

    /** One server tick: GONE once the bot left (kicked, banned, disconnected), LEAVING while the server removes it. */
    Tick tick(long now) {
        if (life.leaving()) return Tick.LEAVING;
        DisconnectionDetails kick = FoliaConnections.pollDisconnect(connection);
        if (kick != null) listener.disconnect(kick);
        if (!connection.isConnected()) return life.leave() ? Tick.GONE : Tick.LEAVING;
        ServerPlayer p = player();
        FoliaConnections.detach(p, connection);
        link.runTasks();
        if (p.isDeadOrDying()) {
            stopWork("died");
            if (++deadTicks >= RESPAWN_TICKS) {
                deadTicks = 0;
                listener.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            }
            return Tick.ALIVE;
        }
        deadTicks = 0;
        settle();
        behavior.tick(this, now);
        boolean worked = walk != null || dig != null || jumpNext;
        if (walk != null) {
            String why = walk.tick(body, now);
            if (why != null) finishWalk(why);
        }
        if (dig != null) {
            String why = dig.tick(this, p, now);
            if (why != null) finishDig(why);
        }
        if (jumpNext) {
            jumpNext = false;
            p.setJumping(p.onGround());
        } else if (walk == null) {
            p.setJumping(false);
        }
        // A real client's movement packets make the server tick the player; a bot has none, so tick it here.
        if (worked || !idle(p) || ticks % IDLE_TICK_EVERY == 0) p.doTick();
        ticks++;
        return Tick.ALIVE;
    }

    /** A light bot standing still with nothing going on; its physics can skip most ticks. */
    private boolean idle(ServerPlayer p) {
        return spec.profile() == Profile.LIGHT && p.onGround() && !p.isInWater() && !p.isInLava() && p.hurtTime == 0
                && p.getRemainingFireTicks() <= 0 && p.getActiveEffects().isEmpty() && p.getDeltaMovement().lengthSqr() < 1e-4;
    }

    /**
     * Sends what a real client sends on its own: the teleport confirmation after every server-side move (join,
     * /tp, respawn) and "world loaded" after joining or respawning. Until then the server ignores the player's
     * block and entity interactions.
     */
    void settle() {
        int teleport = link.pendingTeleport.getAndSet(-1);
        if (teleport >= 0) ServerCompat.get().acceptTeleport(listener, listener.player, teleport);
        if (!listener.hasClientLoaded()) listener.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    /** Leaves the server now (quit event, player data saved). */
    void remove() {
        stopWork("removed");
        behavior.clear(this);
        life.remove();
    }

    /** The player entity was removed from the server (its scheduler retired): end what the bot was doing. */
    void retired() {
        stopWork("removed");
        life.retired();
    }

    void forgetPlayerFiles() {
        life.forgetPlayerFiles(uuid);
    }

    private void stopWork(String why) {
        if (walk != null) finishWalk(why);
        if (dig != null) finishDig(why);
    }

    // --- BotControl: what behaviors, scripts and actions drive ---

    @Override
    public Position position() {
        return body.position();
    }

    @Override
    public boolean alive() {
        return !life.leaving() && !player().isDeadOrDying();
    }

    @Override
    public CompletableFuture<JsonObject> moveTo(WalkOptions o) {
        stopWork("replaced");
        Walker w = Walker.start(body, o, svc.pathBudget(), svc.pathCache(), System.currentTimeMillis());
        walk = w;
        return w.done();
    }

    @Override
    public void stopMoving() {
        if (walk != null) finishWalk("stopped");
    }

    @Override
    public void chat(String text) {
        bukkit().chat(text);
    }

    /** Sends the command the way a client does (the chat-command packet), so PlayerCommandPreprocessEvent fires. */
    @Override
    public void command(String command) {
        listener.handleChatCommand(new ServerboundChatCommandPacket(command));
    }

    @Override
    public GuiView gui() {
        return BotJson.gui(this, true);
    }

    /** One container click through the server's handler; an empty prediction map makes the server resync afterwards. */
    @Override
    public void click(RawClick c) {
        AbstractContainerMenu menu = player().containerMenu;
        listener.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) c.slot(), (byte) c.button(),
                ContainerInput.valueOf(c.mode().name()), new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
    }

    @Override
    public void closeScreen() {
        listener.handleContainerClose(new ServerboundContainerClosePacket(player().containerMenu.containerId));
    }

    @Override
    public void look(float yaw, float pitch) {
        ServerPlayer sp = player();
        sp.setYRot(yaw);
        sp.setXRot(Math.clamp(pitch, -90f, 90f));
        sp.setYHeadRot(yaw);
        sp.setYBodyRot(yaw);
    }

    @Override
    public void swing() {
        ServerCompat.get().swing(listener, InteractionHand.MAIN_HAND);
    }

    /** Reads the other player's position fields without touching their thread (racy, as a client's view is). */
    @Override
    public Optional<Position> playerPosition(String name) {
        Player other = Bukkit.getPlayerExact(name);
        if (other == null) return Optional.empty();
        ServerPlayer sp = ((CraftPlayer) other).getHandle();
        if (sp.level() != player().level()) return Optional.empty();
        Vec3 v = sp.position();
        Position at = new Position(v.x, v.y, v.z);
        return at.distance(position()) <= SEE_RANGE ? Optional.of(at) : Optional.empty();
    }

    // --- other verbs ---

    /** Changes the client settings mid-session (ClientInformation, brand, latency); locale-change events fire. */
    void settings(ClientSettings s) {
        settings = s;
        player().updateOptions(BotJoin.information(s, spec.profile()));
        BotJoin.applySettings(this, s);
    }

    CompletableFuture<JsonObject> breakBlock(Plugin plugin, BlockPos pos, Direction face, long timeoutMs) {
        stopWork("replaced");
        dig = Dig.start(this, plugin, pos, face, timeoutMs);
        return dig.done;
    }

    /** Jumps on the next tick, if standing on something. */
    void jump() {
        jumpNext = true;
    }

    void sprint(boolean on) {
        listener.handlePlayerCommand(new ServerboundPlayerCommandPacket(player(),
                on ? ServerboundPlayerCommandPacket.Action.START_SPRINTING : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
    }

    private void finishWalk(String reason) {
        Walker w = walk;
        walk = null;
        w.finish(body, reason);
    }

    private void finishDig(String reason) {
        Dig d = dig;
        dig = null;
        d.finish(reason);
    }
}
