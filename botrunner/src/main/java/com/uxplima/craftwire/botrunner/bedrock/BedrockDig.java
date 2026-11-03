package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bedrock.packet.ItemUse;
import com.uxplima.craftwire.bedrock.packet.PlayOut;
import com.uxplima.craftwire.bedrock.packet.TransactionOut;
import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.core.AgentError;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Breaking one block the way a Bedrock client does. With server-authoritative block breaking (StartGame) the
 * actions ride on PlayerAuthInput: start, a crack action each tick, then predict-destroy with a break interaction.
 * Without it they are PlayerAction packets and a break transaction. The block counts as broken when the server's
 * block update says so. Runner loop thread.
 */
final class BedrockDig {
    static final double REACH = 6.0;
    private static final int START_BREAK = 0;
    private static final int ABORT_BREAK = 1;
    private static final int STOP_BREAK = 2;
    private static final int CRACK_BREAK = 18;
    private static final int PREDICT_DESTROY = 26;
    /** How long to wait for the server's block update after the bot finished, before calling it refused. */
    private static final int SETTLE_TICKS = 20;

    private final BlockPoint pos;
    private final int face;
    private final int state;
    private final String block;
    private final int needed;
    private final long deadline;
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private int ticks;
    private int afterStop = -1;

    private BedrockDig(BlockPoint pos, int face, int state, String block, int needed, long deadline) {
        this.pos = pos;
        this.face = face;
        this.state = state;
        this.block = block;
        this.needed = needed;
        this.deadline = deadline;
    }

    static CompletableFuture<JsonObject> start(BedrockBot b, BlockPoint pos, Integer face, long timeoutMs) {
        int state = b.world.state(pos.x(), pos.y(), pos.z());
        if (state < 0) {
            throw new AgentError("BLOCK_UNKNOWN", b.name() + " has not received the chunk at " + pos.x() + " " + pos.y() + " " + pos.z(),
                    "move_to closer first; Bedrock bots in world mode none know no blocks.");
        }
        String block = b.world.palette().blockId(state);
        if (state == 0) throw new AgentError("NOTHING_TO_BREAK", "There is no block there", "Check the coordinates with world_query.");
        Position c = pos.center();
        double d = BedrockInteract.eyeDistance(b, c.x(), c.y(), c.z());
        if (d > REACH + 1.0) {
            throw new AgentError("OUT_OF_REACH", String.format(Locale.ROOT, "The block is %.1f blocks away; reach is %.1f", d, REACH),
                    "move_to next to the block first.");
        }
        BedrockInteract.lookAt(b, c.x(), c.y(), c.z());
        int hit = face != null ? face : BedrockInteract.faceTowards(b, c);
        String tool = b.items.name(b.inventory.hotbar(b.life.heldSlot()).networkId());
        int needed = b.life.creative() ? 0 : BedrockDigTime.ticks(block, tool);
        BedrockDig dig = new BedrockDig(pos, hit, state, block, needed, System.currentTimeMillis() + timeoutMs);
        if (needed < 0) {
            dig.finish("unbreakable");
            return dig.done;
        }
        dig.action(b, START_BREAK);
        b.swing();
        b.digging(dig);
        return dig.done;
    }

    /** One tick; true once the dig is over. */
    boolean tick(BedrockBot b, long now) {
        if (b.world.state(pos.x(), pos.y(), pos.z()) != state) return finish("broken");
        if (now >= deadline) {
            action(b, ABORT_BREAK);
            return finish("timeout");
        }
        if (afterStop >= 0) {
            // A server that did not take the predicted break gets it again once, as a client re-sends on lag.
            if (++afterStop == SETTLE_TICKS / 2) destroy(b);
            return afterStop > SETTLE_TICKS && finish("refused");
        }
        ticks++;
        if (ticks % 5 == 0) b.swing();
        if (ticks < needed) {
            if (b.serverAuthBreaking()) b.moves.blockAction(CRACK_BREAK, pos.x(), pos.y(), pos.z(), face);
            return false;
        }
        destroy(b);
        afterStop = 0;
        return false;
    }

    private void destroy(BedrockBot b) {
        Position at = b.position();
        int slot = b.life.heldSlot();
        ItemUse use = new ItemUse(ItemUse.BREAK_BLOCK, pos.x(), pos.y(), pos.z(), face, slot, b.inventory.hotbar(slot), (float) at.x(),
                (float) (at.y() + BedrockEntities.EYE), (float) at.z(), 0, 0, 0, Math.max(0, b.world.palette().runtimeId(state)));
        if (b.serverAuthBreaking()) {
            b.moves.blockAction(PREDICT_DESTROY, pos.x(), pos.y(), pos.z(), face);
            b.moves.itemUse(use);
        } else {
            b.send(TransactionOut.useItem(use, b.items.shieldId()));
            action(b, STOP_BREAK);
        }
    }

    private void action(BedrockBot b, int action) {
        if (b.serverAuthBreaking()) b.moves.blockAction(action, pos.x(), pos.y(), pos.z(), face);
        else b.send(PlayOut.playerAction(b.life.runtimeId(), action, pos.x(), pos.y(), pos.z(), face));
    }

    void cancel(BedrockBot b, String reason) {
        if (!done.isDone()) action(b, ABORT_BREAK);
        finish(reason);
    }

    private boolean finish(String reason) {
        JsonObject r = new JsonObject();
        r.addProperty("broken", reason.equals("broken"));
        if (!reason.equals("broken")) r.addProperty("reason", reason);
        r.addProperty("block", block);
        r.addProperty("x", pos.x());
        r.addProperty("y", pos.y());
        r.addProperty("z", pos.z());
        r.addProperty("ticks", ticks);
        done.complete(r);
        return true;
    }
}
