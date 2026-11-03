package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.core.AgentError;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One break_block for a Java bot, as a survival client digs: start digging, swing every tick, and send the finish
 * once the dig time for the held tool has passed (the server checks the time and refuses an early finish). The
 * time is the vanilla formula (tool speed, hardness, the right tool, standing) without enchantments or effects,
 * which can only make the bot slower than a player, never too fast. Creative mode breaks at once. Runner loop thread.
 */
final class JavaDig {
    private static final double REACH = 4.5;
    /** Ticks the server gets to break the block after the bot finished digging. */
    private static final int SETTLE_TICKS = 10;

    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private final BlockPos pos;
    private final Direction face;
    private final long deadline;
    private final int state;
    private final String block;
    private float progress;
    private int ticks;
    private int afterStop = -1;

    private JavaDig(BlockPos pos, Direction face, long deadline, int state, String block) {
        this.pos = pos;
        this.face = face;
        this.deadline = deadline;
        this.state = state;
        this.block = block;
    }

    static CompletableFuture<JsonObject> start(JavaBot b, BlockPos pos, Direction face, long timeoutMs) {
        int state = b.world.state(pos.getX(), pos.getY(), pos.getZ());
        if (state < 0) {
            throw new AgentError("BLOCK_UNKNOWN", b.name() + " has not received the chunk at " + pos.toShortString(),
                    "move_to closer first; Java bots in world mode none know no blocks.");
        }
        if (Block.BLOCK_STATE_REGISTRY.byId(state) == null || Block.BLOCK_STATE_REGISTRY.byId(state).isAir()) {
            throw new AgentError("NOTHING_TO_BREAK", "There is no block at " + pos.toShortString(), "Check the coordinates with world_query.");
        }
        Vec3 c = Vec3.atCenterOf(pos);
        double d = b.screens.eyeDistance(c.x, c.y, c.z);
        if (d > REACH + 1.0) {
            throw new AgentError("OUT_OF_REACH", String.format(Locale.ROOT, "The block is %.1f blocks away; reach is %.1f", d, REACH),
                    "move_to next to the block first.");
        }
        b.screens.lookAt(c.x, c.y, c.z);
        Direction hit = face != null ? face : towards(b, c);
        JavaDig dig = new JavaDig(pos, hit, System.currentTimeMillis() + timeoutMs, state, b.ctx.blocks().blockId(state));
        dig.action(b, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        b.swing();
        if (b.creative) dig.afterStop = 0;
        b.digging(dig);
        return dig.done;
    }

    private static Direction towards(JavaBot b, Vec3 c) {
        Position eye = b.position().add(0, 1.62, 0);
        return Direction.getApproximateNearest(eye.x() - c.x, eye.y() - c.y, eye.z() - c.z);
    }

    /** One tick; true once the dig is over (its future completed). */
    boolean tick(JavaBot b, long now) {
        if (b.world.state(pos.getX(), pos.getY(), pos.getZ()) != state) return finish("broken");
        if (now >= deadline) {
            action(b, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            return finish("timeout");
        }
        if (afterStop >= 0) return ++afterStop > SETTLE_TICKS && finish("refused");
        float perTick = perTick(b);
        if (perTick <= 0) {
            action(b, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            return finish("unbreakable");
        }
        ticks++;
        progress += perTick;
        b.swing();
        if (progress >= 1f) {
            action(b, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            afterStop = 0;
        }
        return false;
    }

    /** BlockBehaviour.getDestroyProgress without the player's attributes, enchantments and effects. */
    private float perTick(JavaBot b) {
        BlockState s = Block.BLOCK_STATE_REGISTRY.byId(state);
        float hardness = s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness < 0) return 0;
        if (hardness == 0) return 1;
        ItemStack held = b.inventory.item(36 + b.life.heldSlot());
        float speed;
        boolean right;
        try {
            speed = held.getDestroySpeed(s);
            right = !s.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(s);
        } catch (RuntimeException e) {
            // Tool rules name block tags; if the server's tags could not be bound, dig at the slowest legal pace.
            speed = 1f;
            right = !s.requiresCorrectToolForDrops();
        }
        if (!b.body.onGround()) speed /= 5f;
        return speed / hardness / (right ? 30f : 100f);
    }

    private void action(JavaBot b, ServerboundPlayerActionPacket.Action a) {
        b.send(new ServerboundPlayerActionPacket(a, pos, face, b.nextSequence()));
    }

    void cancel(String reason) {
        finish(reason);
    }

    private boolean finish(String reason) {
        JsonObject r = new JsonObject();
        r.addProperty("broken", reason.equals("broken"));
        if (!reason.equals("broken")) r.addProperty("reason", reason);
        r.addProperty("block", block);
        r.addProperty("x", pos.getX());
        r.addProperty("y", pos.getY());
        r.addProperty("z", pos.getZ());
        r.addProperty("ticks", ticks);
        done.complete(r);
        return true;
    }
}
