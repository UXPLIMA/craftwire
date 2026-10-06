package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.plugin.Plugin;

/**
 * One break_block: digs like a survival player — start digging, swing every tick, and finish once the block's dig
 * time for the held tool has passed (the server checks the time itself). Server thread only.
 */
final class Dig {
    /** Ticks the server gets to break the block after the bot finished digging. */
    private static final int SETTLE_TICKS = 10;

    final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private final BlockPos pos;
    private final Direction face;
    private final long deadline;
    private final String block;
    private final Watch watch;
    private float progress;
    private int ticks;
    private int afterStop = -1;

    private Dig(BlockPos pos, Direction face, long deadline, String block, Watch watch) {
        this.pos = pos;
        this.face = face;
        this.deadline = deadline;
        this.block = block;
        this.watch = watch;
    }

    static Dig start(Bot b, Plugin plugin, BlockPos pos, Direction face, long timeoutMs) {
        ServerPlayer p = b.player();
        ServerLevel level = (ServerLevel) p.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            throw new AgentError("NOTHING_TO_BREAK", "There is no block at " + pos.toShortString(), "Check the coordinates with world_query.");
        }
        if (!p.isWithinBlockInteractionRange(pos, 1.0)) {
            double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
            throw new AgentError("OUT_OF_REACH", String.format(Locale.ROOT, "The block is %.1f blocks away; reach is %.1f", d, p.blockInteractionRange()),
                    "move_to next to the block first.");
        }
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        Direction hitFace = face != null ? face : Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        BotActions.face(p, Steering.yaw(c.x - eye.x, c.z - eye.z), Steering.pitch(c.x - eye.x, c.y - eye.y, c.z - eye.z));
        Watch watch = new Watch(b.uuid(), pos);
        Bukkit.getPluginManager().registerEvents(watch, plugin);
        Dig dig = new Dig(pos, hitFace, System.currentTimeMillis() + timeoutMs, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), watch);
        b.listener().handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, hitFace, b.nextSequence()));
        ServerCompat.get().swingAfterInteraction(b.listener(), InteractionHand.MAIN_HAND);
        return dig;
    }

    /** One tick. Returns why digging ended, or null while it goes on. */
    String tick(Bot b, ServerPlayer p, long now) {
        ServerLevel level = (ServerLevel) p.level();
        BlockState state = level.getBlockState(pos);
        if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(block)) return "broken";
        if (watch.cancelled != null) return "cancelled";
        if (now >= deadline) {
            b.listener().handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, pos, face, b.nextSequence()));
            return "timeout";
        }
        if (afterStop >= 0) return ++afterStop > SETTLE_TICKS ? "refused" : null;
        float perTick = state.getDestroyProgress(p, level, pos);
        if (perTick <= 0) return "unbreakable";
        ticks++;
        progress += perTick;
        ServerCompat.get().swingAfterInteraction(b.listener(), InteractionHand.MAIN_HAND);
        if (progress >= 1f) {
            b.listener().handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, b.nextSequence()));
            afterStop = 0;
        }
        return null;
    }

    void finish(String reason) {
        HandlerList.unregisterAll(watch);
        JsonObject r = new JsonObject();
        r.addProperty("broken", reason.equals("broken"));
        if (!reason.equals("broken")) r.addProperty("reason", reason);
        r.addProperty("block", block);
        r.addProperty("x", pos.getX());
        r.addProperty("y", pos.getY());
        r.addProperty("z", pos.getZ());
        r.addProperty("ticks", ticks);
        if (watch.cancelled != null) r.addProperty("cancelledBy", watch.cancelled);
        done.complete(r);
    }

    /** Whether a plugin cancelled the dig (BlockDamageEvent) or the break (BlockBreakEvent) of this bot's block. */
    static final class Watch implements Listener {
        private final UUID player;
        private final BlockPos pos;
        volatile String cancelled;

        Watch(UUID player, BlockPos pos) {
            this.player = player;
            this.pos = pos;
        }

        private boolean mine(org.bukkit.entity.Player who, org.bukkit.block.Block b) {
            return who.getUniqueId().equals(player) && b.getX() == pos.getX() && b.getY() == pos.getY() && b.getZ() == pos.getZ();
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDamage(BlockDamageEvent e) {
            if (e.isCancelled() && mine(e.getPlayer(), e.getBlock())) cancelled = "BlockDamageEvent";
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onBreak(BlockBreakEvent e) {
            if (e.isCancelled() && mine(e.getPlayer(), e.getBlock())) cancelled = "BlockBreakEvent";
        }
    }
}
