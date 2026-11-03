package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bedrock.crypto.Jwt;
import com.uxplima.craftwire.bedrock.packet.BedrockVersion;
import com.uxplima.craftwire.bedrock.packet.Clientbound;
import com.uxplima.craftwire.bedrock.packet.ItemPackets;
import com.uxplima.craftwire.bedrock.packet.PlayOut;
import com.uxplima.craftwire.bedrock.packet.StackRequest;
import com.uxplima.craftwire.bot.behavior.BehaviorSlot;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.bot.inbox.Inbox;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.bot.model.BotState;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.CellPhysics;
import com.uxplima.craftwire.bot.move.Walker;
import com.uxplima.craftwire.bot.move.WalkOptions;
import com.uxplima.craftwire.botrunner.bot.JoinSpec;
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.core.AgentError;
import io.netty.buffer.ByteBuf;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One external Bedrock Edition bot: a connection of our own Bedrock protocol ({@link BedrockSession}) plus the client
 * state a Bedrock game keeps (world view, entities, inventory, forms, HUD, physics), driven by the runner loop at 20
 * ticks a second with one PlayerAuthInput per tick. Runner loop thread, except the {@link BedrockSession.Sink}
 * callbacks, which only hand work to the loop.
 */
final class BedrockBot implements RunnerBot, BedrockSession.Sink {
    final BedrockContext ctx;
    final JoinSpec spec;
    final UUID uuid;
    final BedrockHud hud = new BedrockHud();
    final BedrockItems items = new BedrockItems();
    final BedrockInventory inventory = new BedrockInventory(items);
    final BedrockClicks clicks = new BedrockClicks(inventory);
    final BedrockForms forms = new BedrockForms();
    final BedrockLife life = new BedrockLife();
    final BedrockWorld world;
    final BedrockEntities entities;
    final CellPhysics body;
    final BedrockMoves moves;
    final BedrockPacks packs;
    private final Inbox inbox = new Inbox(200);
    private final BehaviorSlot behavior = new BehaviorSlot();
    private final BedrockPlay play = new BedrockPlay(this);
    private final CompletableFuture<RunnerBot> joined = new CompletableFuture<>();
    private final KeyPair key = Jwt.newKeyPair();
    private final int view;
    private volatile BedrockSession session;
    /** Set once by the join (from the server's pong) before the connection starts; read on every thread after. */
    private volatile BedrockVersion version = BedrockVersion.LATEST;
    private volatile boolean connected = true;
    private boolean serverAuthBreaking;
    private Walker walker;
    private BedrockDig dig;
    private String goneReason;
    private boolean cleaned;

    BedrockBot(BedrockContext ctx, JoinSpec spec, UUID uuid, int view) {
        this.ctx = ctx;
        this.spec = spec;
        this.uuid = uuid;
        this.view = view;
        boolean shared = !Params.optString(spec.raw(), "worldMode").orElse("shared").equals("none");
        this.world = new BedrockWorld(ctx.services().worlds(), address(), shared, this, this::send);
        this.entities = new BedrockEntities(world::sharedWorld, this);
        this.body = new CellPhysics(world::view, world::dimension, on -> { }, b -> BedrockInteract.useOn(this, b,
                BedrockInteract.faceTowards(this, b.center())));
        this.moves = new BedrockMoves(body);
        this.packs = new BedrockPacks(spec.packs(), spec.verifyPacks(), this::send);
    }

    KeyPair key() {
        return key;
    }

    BedrockVersion version() {
        return version;
    }

    void version(BedrockVersion v) {
        version = v;
    }

    CompletableFuture<RunnerBot> joined() {
        return joined;
    }

    /** Event loop thread: the connection is up. A bot removed (or timed out) while connecting closes it at once. */
    void session(BedrockSession s) {
        session = s;
        if (!connected) s.close("removed while joining");
    }

    // --- BedrockSession.Sink: event loop threads, handed to the runner loop ---

    @Override
    public void packet(Clientbound p) {
        ctx.services().loop().execute(() -> play.apply(p, System.currentTimeMillis()));
    }

    @Override
    public void closed(String reason) {
        ctx.services().loop().execute(() -> {
            connected = false;
            goneReason = reason;
            if (!joined.isDone()) joined.completeExceptionally(BedrockJoin.failure(reason));
        });
    }

    // --- runner loop ---

    void send(ByteBuf p) {
        BedrockSession s = session;
        if (s != null) s.send(p);
        else p.release();
    }

    void kicked(String message) {
        leave("kicked: " + BedrockHud.plain(message));
    }

    int view() {
        return view;
    }

    void serverAuthBreaking(boolean on) {
        serverAuthBreaking = on;
    }

    boolean serverAuthBreaking() {
        return serverAuthBreaking;
    }

    void stackRequest(List<StackRequest.Action> actions, StackRequest.Slot a, StackRequest.Slot b) {
        send(StackRequest.packet(List.of(clicks.unpredicted(actions, a, b)), items.shieldId()));
    }

    void answerForm(String button, Map<String, String> inputs) {
        BedrockForms.Answer a = forms.answer(button, inputs);
        if (a.form() != null) {
            send(PlayOut.formResponse(a.form().id(), a.json()));
            return;
        }
        long rid = entities.runtimeOf(a.npc().entityId());
        if (rid < 0) throw new AgentError("ENTITY_NOT_FOUND", "The NPC of this dialogue is out of sight", "move_to the NPC, then answer again.");
        send(PlayOut.npcAction(rid, a.npcButton(), a.npc().scene()));
    }

    void closeForm() {
        BedrockForms.Answer a = forms.close();
        if (a != null) send(PlayOut.formResponse(a.form().id(), null));
    }

    void digging(BedrockDig d) {
        if (dig != null) dig.cancel(this, "replaced");
        dig = d;
    }

    @Override
    public Tick tick(long now) {
        if (!connected) {
            cleanup(goneReason());
            return Tick.GONE;
        }
        if (!life.spawned()) return Tick.ALIVE;
        if (life.respawnDue()) BedrockSpawn.requestRespawn(this);
        if (dig != null && dig.tick(this, now)) dig = null;
        if (walker != null) {
            String why = walker.tick(body, now);
            if (why != null) endWalk(why);
        }
        body.step();
        send(moves.input().encode(items.shieldId()));
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
        if (dig != null) dig.cancel(this, reason);
        dig = null;
        world.leave();
    }

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public BotKind kind() {
        return BotKind.BEDROCK;
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
                    "Spawn Bedrock bots with worldMode:'shared' to walk, or teleport this one (server_command tp).");
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
        send(PlayOut.chat(name(), text, ""));
    }

    @Override
    public void command(String command) {
        send(PlayOut.command(command, life.uniqueId()));
    }

    @Override
    public GuiView gui() {
        GuiView v = inventory.view();
        if (v.open()) return v;
        JsonObject screen = forms.json();
        if (screen == null) return v;
        return new GuiView(false, v.title(), v.type(), v.containerId(), v.stateId(), v.slotCount(), v.topSize(), v.slots(), v.carried(), screen);
    }

    @Override
    public void click(RawClick c) {
        StackRequest r = clicks.plan(c);
        if (r != null) send(StackRequest.packet(List.of(r), items.shieldId()));
    }

    @Override
    public void closeScreen() {
        if (inventory.menuOpen()) {
            ItemPackets.ContainerOpen o = inventory.openMenu();
            send(PlayOut.closeContainer(o.window(), o.type()));
            inventory.closed();
        } else {
            closeForm();
        }
    }

    @Override
    public void look(float yaw, float pitch) {
        body.look(yaw, pitch);
    }

    @Override
    public void swing() {
        send(PlayOut.swing(life.runtimeId(), version));
    }

    @Override
    public Optional<Position> playerPosition(String name) {
        return entities.player(name);
    }

    @Override
    public Inbox inbox() {
        return inbox;
    }

    @Override
    public BehaviorSlot behavior() {
        return behavior;
    }

    @Override
    public BotState state() {
        Position pos = body.position();
        String menu = inventory.menuOpen() ? inventory.title() : null;
        return new BotState(name(), BotKind.BEDROCK, uuid.toString(), group(), world.dimension(), pos, body.yaw(), body.pitch(), life.health(),
                life.food(), life.gameMode(), body.onGround(), life.dead(), moving(), dig != null, body.sneaking(), body.sprintingNow(),
                life.heldSlot(), items.info(inventory.hotbar(life.heldSlot())), inventory.byInventoryIndex(), menu);
    }

    @Override
    public JsonObject summary() {
        JsonObject o = BotState.summary(name(), BotKind.BEDROCK, uuid.toString(), group(), world.dimension(), body.position());
        o.addProperty("server", address());
        return o;
    }

    @Override
    public Optional<CompletableFuture<JsonElement>> action(String action, JsonObject p) {
        return BedrockActions.run(this, action, p);
    }

    @Override
    public void selectHotbar(int slot) {
        send(PlayOut.selectHotbar(life.runtimeId(), inventory.hotbar(slot), slot, items.shieldId()));
        life.heldSlot(slot);
    }

    @Override
    public void leave(String reason) {
        BedrockSession s = session;
        if (s != null) s.close(reason);
        connected = false;
        cleanup(reason);
        if (!joined.isDone()) joined.completeExceptionally(BedrockJoin.failure(reason));
    }

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public String goneReason() {
        return goneReason == null ? "disconnected" : goneReason;
    }
}
