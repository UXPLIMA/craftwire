package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.bot.path.Cell;
import com.uxplima.craftwire.paper.bot.path.PaperBlockView;
import com.uxplima.craftwire.paper.bot.path.Pathfinder;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One move_to: a path from the pathfinder (or a straight line), followed tick by tick with the player's own
 * physics. Every method runs on the server thread.
 */
final class Walk {
    static final int MAX_DISTANCE = 256;
    private static final int MAX_REPLANS = 3;
    /** Stretches walked towards a target beyond the loaded chunks, planning again at the end of each. */
    private static final int MAX_STRETCHES = 64;
    private static final int DOOR_WAIT_TICKS = 20;

    final double x, y, z, tolerance;
    final boolean sprint;
    final long deadline;
    final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private final Pathfinder.Options options;
    private final boolean usePath;
    private final boolean partial;
    private List<Pathfinder.Step> steps = List.of();
    private int index;
    private int replans;
    private int doorTicks;
    private boolean complete = true;
    private Pathfinder.Step closest;
    /** Why the walk ends at the end of an incomplete path: height, no_path; null when it plans again there. */
    private String endReason;
    private int stretches;
    private Steering.Progress progress = new Steering.Progress(0.3, 1500);

    Walk(double x, double y, double z, double tolerance, boolean sprint, long deadline, boolean usePath, Pathfinder.Options options, boolean partial) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.tolerance = tolerance;
        this.sprint = sprint;
        this.deadline = deadline;
        this.usePath = usePath;
        this.options = options;
        this.partial = partial;
    }

    /** Plans the path from where the player stands. Returns why the walk cannot start, or null. */
    String plan(ServerPlayer p) {
        if (!usePath) return null;
        Vec3 pos = p.position();
        if (Steering.horizontal(x - pos.x, z - pos.z) > MAX_DISTANCE) {
            throw new AgentError("TOO_FAR", "The target is more than " + MAX_DISTANCE + " blocks away",
                    "move_to a closer point first, or teleport the bot (server_command tp).");
        }
        Pathfinder.Result r = Pathfinder.find(new PaperBlockView((ServerLevel) p.level()),
                floor(pos.x), floor(pos.y + 1e-3), floor(pos.z), floor(x), floor(y), floor(z), options);
        complete = r.complete();
        closest = r.closest();
        index = 1;   // step 0 is where the bot stands
        progress = new Steering.Progress(0.3, 1500);
        endReason = null;
        if (r.complete()) {
            steps = r.steps();
            return null;
        }
        boolean moves = r.steps().size() > 1;
        if (closest.x() == floor(x) && closest.z() == floor(z)) {
            endReason = "height";   // right below or above the target: walking cannot get closer
        } else if (r.unloaded() && moves) {
            endReason = null;       // the way goes on into chunks that load as the bot walks: plan again there
        } else if (partial && moves) {
            endReason = "no_path";
        } else {
            steps = List.of();
            return "no_path";
        }
        steps = r.steps();
        return null;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** One tick of walking. Returns why the walk ended, or null while it goes on. */
    String tick(Bot b, ServerPlayer p, long now) {
        if (now >= deadline) return "timeout";
        Vec3 pos = p.position();
        if (index >= steps.size()) return approach(p, pos, now);

        // Skip ahead when the bot already stands at a later step (it cut a corner, or fell further).
        for (int k = Math.min(steps.size() - 1, index + 3); k >= index; k--) {
            if (at(steps.get(k), pos, p)) {
                index = k + 1;
                doorTicks = 0;
                progress = new Steering.Progress(0.3, 1500);
                return index >= steps.size() ? approach(p, pos, now) : null;
            }
        }
        Pathfinder.Step s = steps.get(index);
        double cx = s.x() + 0.5;
        double cz = s.z() + 0.5;
        double d = Steering.horizontal(cx - pos.x, cz - pos.z);

        boolean waiting = false;
        if (s.move() == Pathfinder.Move.DOOR && d < 2.5) {
            BlockPos door = closedDoor((ServerLevel) p.level(), s);
            if (door != null) {
                if (doorTicks == 0) open(b, p, door);
                if (++doorTicks > DOOR_WAIT_TICKS) return "blocked";
                waiting = true;
            }
        }
        if (!waiting && progress.stuck(d + Math.abs(s.standY() - pos.y), now)) {
            if (++replans > MAX_REPLANS) return "stuck";
            String why = plan(p);
            return why;
        }
        steer(p, cx, cz, s.standY(), waiting);
        return null;
    }

    private boolean at(Pathfinder.Step s, Vec3 pos, ServerPlayer p) {
        double d = Steering.horizontal(s.x() + 0.5 - pos.x, s.z() + 0.5 - pos.z);
        double dy = pos.y - s.standY();
        if (s.move() == Pathfinder.Move.CLIMB) return d < 0.6 && Math.abs(dy) < 0.4;
        return d < 0.4 && dy > -0.5 && dy < 1.0 && (p.onGround() || p.isInWater() || p.onClimbable());
    }

    /** The last stretch: from the final block to the exact target point, like the straight-line walk. */
    private String approach(ServerPlayer p, Vec3 pos, long now) {
        if (usePath && !complete) {
            if (endReason != null) return endReason;
            if (++stretches > MAX_STRETCHES) return "stuck";
            return plan(p);
        }
        double distance = Steering.horizontal(x - pos.x, z - pos.z);
        if (distance <= tolerance) {
            // Over or under a target that is not at ground level, walking cannot get any closer.
            return Math.abs(y - pos.y) <= 1.5 ? "arrived" : "height";
        }
        if (progress.stuck(distance, now)) return "stuck";
        steer(p, x, z, y, false);
        return null;
    }

    private void steer(ServerPlayer p, double tx, double tz, double standY, boolean waiting) {
        Vec3 pos = p.position();
        float yaw = Steering.yaw(tx - pos.x, tz - pos.z);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        boolean ladder = p.onClimbable();
        boolean descending = ladder && standY < pos.y - 0.2;
        float speed = p.isShiftKeyDown() ? 0.3f : 1.0f;
        p.zza = waiting || descending ? 0f : speed;
        p.setSprinting(sprint && !waiting && !p.isShiftKeyDown());
        boolean jump;
        if (p.isInWater()) jump = standY >= pos.y - 0.2;
        else if (ladder) jump = standY > pos.y + 0.2;
        else jump = p.onGround() && (standY - pos.y > 0.6 || p.horizontalCollision);
        p.setJumping(jump && !waiting);
    }

    /** The closed door or gate at a DOOR step (its lower block), or null once it is open. */
    private static BlockPos closedDoor(ServerLevel level, Pathfinder.Step s) {
        PaperBlockView view = new PaperBlockView(level);
        if (view.cell(s.x(), s.y(), s.z()).kind() == Cell.Kind.DOOR) return new BlockPos(s.x(), s.y(), s.z());
        if (view.cell(s.x(), s.y() + 1, s.z()).kind() == Cell.Kind.DOOR) return new BlockPos(s.x(), s.y() + 1, s.z());
        return null;
    }

    /** Right-clicks the door like a player: PlayerInteractEvent fires, so a protection plugin can refuse. */
    private static void open(Bot b, ServerPlayer p, BlockPos door) {
        Vec3 hit = Vec3.atCenterOf(door);
        Vec3 eye = p.getEyePosition();
        Direction face = Direction.getApproximateNearest(eye.x - hit.x, eye.y - hit.y, eye.z - hit.z);
        BotActions.face(p, Steering.yaw(hit.x - eye.x, hit.z - eye.z), Steering.pitch(hit.x - eye.x, hit.y - eye.y, hit.z - eye.z));
        b.listener().handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, face, door, false), b.nextSequence()));
        ServerCompat.get().swingAfterInteraction(b.listener(), InteractionHand.MAIN_HAND);
    }

    /** What the result says about the path. */
    void describe(JsonObject r) {
        if (!usePath) return;
        JsonObject path = new JsonObject();
        path.addProperty("nodes", steps.size());
        double length = 0;
        for (int i = 1; i < steps.size(); i++) {
            Pathfinder.Step a = steps.get(i - 1);
            Pathfinder.Step c = steps.get(i);
            length += Math.sqrt(Math.pow(c.x() - a.x(), 2) + Math.pow(c.standY() - a.standY(), 2) + Math.pow(c.z() - a.z(), 2));
        }
        path.addProperty("length", BotJson.round(length));
        path.addProperty("complete", complete);
        r.add("path", path);
        r.addProperty("replans", Math.min(replans, MAX_REPLANS));
        if (!complete && closest != null) {
            JsonObject c = new JsonObject();
            c.addProperty("x", closest.x());
            c.addProperty("y", BotJson.round(closest.standY()));
            c.addProperty("z", closest.z());
            r.add("closest", c);
        }
    }
}
