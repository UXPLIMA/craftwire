package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.behavior.BehaviorSlot;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.bot.inbox.Inbox;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.bot.model.BotState;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.model.Profile;
import com.uxplima.craftwire.bot.move.Walker;
import com.uxplima.craftwire.bot.move.WalkOptions;
import com.uxplima.craftwire.botrunner.bot.JoinSpec;
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.botrunner.javabot.JavaContext;
import com.uxplima.craftwire.botrunner.javabot.session.JavaSession;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.mc.HudCopy;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ContainerInput;

/**
 * One external Java Edition bot: a vanilla-protocol connection ({@link JavaSession}) plus the client state a player's
 * game keeps (inventory, screens, HUD, world view, physics), driven by the runner loop at 20 ticks a second. The
 * session's packets are handed to the loop and applied there by {@link JavaPlay}. Runner loop thread, except the
 * {@link JavaSession.Sink} callbacks, which only hand work to the loop.
 */
public final class JavaBot implements RunnerBot, JavaSession.Sink {
    final JavaContext ctx;
    final JoinSpec spec;
    final UUID uuid;
    final JavaChat chat = new JavaChat();
    final HudCopy hud = new HudCopy();
    final JavaInventory inventory = new JavaInventory();
    final JavaScreens screens = new JavaScreens(this);
    final JavaRecipes recipes = new JavaRecipes();
    final JavaWorld world;
    final JavaPhysics body;
    final JavaPacks packs;
    final JavaMoves moves;
    final JavaLife life = new JavaLife();
    private final BehaviorSlot behavior = new BehaviorSlot();
    private final JavaPlay play = new JavaPlay(this);
    private final CompletableFuture<RunnerBot> joined = new CompletableFuture<>();
    private volatile JavaSession session;
    private volatile boolean connected = true;
    /**
     * Whether the tab list is copied into the HUD. Every join and leave sends each player a tab update, so n bots
     * means n x n rows; light bots start copying on their first hud_read, as plugin bots do.
     */
    boolean tabWatched;
    /** Read by the play codec on the event loop: creative slots encode differently. */
    volatile boolean creative;
    RegistryAccess registries;
    private Walker walker;
    private JavaDig dig;
    private int sequence;
    private String goneReason;
    private boolean cleaned;

    JavaBot(JavaContext ctx, JoinSpec spec, UUID uuid) {
        this.ctx = ctx;
        this.spec = spec;
        this.uuid = uuid;
        this.tabWatched = spec.profile() != Profile.LIGHT;
        boolean shared = !Params.optString(spec.raw(), "worldMode").orElse("shared").equals("none");
        this.world = new JavaWorld(ctx.services().worlds(), ctx.blocks(), ctx.compat(), spec.host() + ":" + spec.port(), shared, this);
        JavaMoves m = new JavaMoves(this);
        this.moves = m;
        this.body = new JavaPhysics(world::view, world::dimension, m::sprint, b -> screens.useBlock(b));
        this.packs = new JavaPacks(spec.packs(), spec.verifyPacks(), ctx.services().packs(), ctx.services().loop()::execute, p -> sendAny(p));
    }

    /** Connects and completes once the bot stands in the world (the server's first position), or fails with why it could not. */
    public static CompletableFuture<RunnerBot> join(JavaContext ctx, JoinSpec spec) {
        return JavaJoin.start(ctx, spec);
    }

    CompletableFuture<RunnerBot> joined() {
        return joined;
    }

    /** Event loop thread: the connection is up. A bot removed (or timed out) while connecting closes it at once. */
    void session(JavaSession s) {
        session = s;
        if (!connected) s.close("removed while joining");
    }

    // --- JavaSession.Sink: event loop threads, handed to the runner loop ---

    @Override
    public void playing(RegistryAccess access) {
        ctx.services().loop().execute(() -> registries = access);
    }

    @Override
    public void packet(Packet<?> packet) {
        ctx.services().loop().execute(() -> play.apply(packet, System.currentTimeMillis()));
    }

    @Override
    public void closed(String reason) {
        ctx.services().loop().execute(() -> {
            connected = false;
            goneReason = reason;
            if (!joined.isDone()) joined.completeExceptionally(JavaJoin.failure(reason));
        });
    }

    // --- runner loop ---

    /** The first position arrived: the bot is in the world. */
    void spawned() {
        if (!joined.isDone()) joined.complete(this);
    }

    void send(Packet<?> p) {
        JavaSession s = session;
        if (s != null) s.send(p);
    }

    /** Sends in any phase (resource pack answers are valid in configuration too). */
    void sendAny(Packet<?> p) {
        JavaSession s = session;
        if (s != null) s.sendCommon(p);
    }

    int nextSequence() {
        return ++sequence;
    }

    @Override
    public Tick tick(long now) {
        if (!connected) {
            cleanup(goneReason == null ? "disconnected" : goneReason);
            return Tick.GONE;
        }
        if (!life.spawned()) return Tick.ALIVE;
        life.tick(this);
        if (dig != null && dig.tick(this, now)) dig = null;
        if (walker != null) {
            String why = walker.tick(body, now);
            if (why != null) endWalk(why);
        }
        boolean moved = body.step();
        moves.tick(moved);
        return Tick.ALIVE;
    }

    private void endWalk(String why) {
        Walker w = walker;
        walker = null;
        w.finish(body, why);
    }

    private void cleanup(String reason) {
        if (cleaned) return;
        cleaned = true;
        if (walker != null) endWalk("disconnected");
        if (dig != null) dig.cancel(reason);
        dig = null;
        world.leave();
    }

    void digging(JavaDig d) {
        if (dig != null) dig.cancel("replaced");
        dig = d;
    }

    boolean digging() {
        return dig != null;
    }

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public BotKind kind() {
        return BotKind.JAVA;
    }

    @Override
    public String group() {
        return spec.group();
    }

    @Override
    public String address() {
        return spec.host() + ":" + spec.port();
    }

    @Override
    public Position position() {
        return body.position();
    }

    @Override
    public boolean alive() {
        return connected && !life.dead();
    }

    @Override
    public boolean moving() {
        return walker != null;
    }

    @Override
    public CompletableFuture<JsonObject> moveTo(WalkOptions o) {
        if (!world.shared()) {
            throw new AgentError("NO_WORLD", name() + " runs in world mode none and knows no blocks to walk on",
                    "Spawn Java bots with worldMode:'shared' to walk, or teleport this one (server_command tp).");
        }
        if (walker != null) endWalk("replaced");
        walker = Walker.start(body, o, ctx.services().pathBudget(), ctx.services().pathCache(), System.currentTimeMillis());
        return walker.done();
    }

    @Override
    public void stopMoving() {
        if (walker != null) endWalk("stopped");
    }

    @Override
    public void chat(String text) {
        send(ctx.compat().chat(text, Instant.now(), ThreadLocalRandom.current().nextLong()));
    }

    @Override
    public void command(String command) {
        send(new ServerboundChatCommandPacket(command));
    }

    @Override
    public GuiView gui() {
        GuiView v = inventory.view();
        if (v.open()) return v;
        JsonObject screen = screens.json();
        if (screen == null) return v;
        return new GuiView(false, v.title(), v.type(), v.containerId(), v.stateId(), v.slotCount(), v.topSize(), v.slots(), v.carried(), screen);
    }

    /** Sent the way the 26.x client sends a click: no predicted slots and an empty carried hash, so the server resyncs. */
    @Override
    public void click(RawClick c) {
        send(new ServerboundContainerClickPacket(inventory.containerId(), inventory.stateId(), (short) c.slot(), (byte) c.button(),
                ContainerInput.valueOf(c.mode().name()), Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
    }

    @Override
    public void closeScreen() {
        if (inventory.menuOpen()) {
            send(new ServerboundContainerClosePacket(inventory.containerId()));
            inventory.closed();
        } else {
            screens.escape();
        }
    }

    @Override
    public void look(float yaw, float pitch) {
        body.look(yaw, pitch);
    }

    @Override
    public void swing() {
        ctx.compat().swing(true).forEach(this::send);
    }

    @Override
    public Optional<Position> playerPosition(String name) {
        return world.player(name);
    }

    @Override
    public Inbox inbox() {
        return chat.inbox();
    }

    @Override
    public BehaviorSlot behavior() {
        return behavior;
    }

    @Override
    public BotState state() {
        Position pos = body.position();
        String menu = inventory.menuOpen() ? inventory.view().title() : null;
        return new BotState(name(), BotKind.JAVA, uuid.toString(), group(), world.dimension(), pos, body.yaw(), body.pitch(), life.health(),
                life.food(), life.gameMode(), body.onGround(), life.dead(), moving(), digging(), body.sneaking(), body.sprintingNow(),
                life.heldSlot(), JavaItems.info(inventory.item(36 + life.heldSlot())), inventory.byInventoryIndex(), menu);
    }

    @Override
    public JsonObject summary() {
        JsonObject o = BotState.summary(name(), BotKind.JAVA, uuid.toString(), group(), world.dimension(), body.position());
        o.addProperty("server", address());
        return o;
    }

    @Override
    public Optional<CompletableFuture<JsonElement>> action(String action, JsonObject p) {
        return JavaActions.run(this, action, p);
    }

    @Override
    public void selectHotbar(int slot) {
        send(new ServerboundSetCarriedItemPacket(slot));
        life.heldSlot(slot);
    }

    @Override
    public void leave(String reason) {
        JavaSession s = session;
        if (s != null) s.close(reason);
        connected = false;
        cleanup(reason);
        if (!joined.isDone()) joined.completeExceptionally(JavaJoin.failure(reason));
    }

    @Override
    public boolean connected() {
        return connected;
    }
}
