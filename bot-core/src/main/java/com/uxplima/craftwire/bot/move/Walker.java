package com.uxplima.craftwire.bot.move;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Jsons;
import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.path.BlockView;
import com.uxplima.craftwire.bot.path.Cell;
import com.uxplima.craftwire.bot.path.PathBudget;
import com.uxplima.craftwire.bot.path.PathCache;
import com.uxplima.craftwire.bot.path.Pathfinder;
import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One move_to on any bot kind: a path from the pathfinder (or a straight line), followed tick by tick through the
 * body's movement keys. The search runs in budgeted slices, so the bot may stand still for a tick or two before it
 * starts walking. Every method runs on the bot's own thread.
 */
public final class Walker {
    public static final int MAX_DISTANCE = 256;
    private static final int MAX_REPLANS = 3;
    /** Stretches walked towards a target beyond the loaded chunks, planning again at the end of each. */
    private static final int MAX_STRETCHES = 64;
    private static final int DOOR_WAIT_TICKS = 20;

    private final WalkOptions o;
    private final long deadline;
    private final PathBudget budget;
    private final PathCache cache;
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();
    private final WalkTrace trace = new WalkTrace();
    private PathPlan plan;
    /** Whether the current plan's search finished and the walk took its steps. */
    private boolean adopted;
    private int index;
    private int replans;
    private int doorTicks;
    private int stretches;
    private Steering.Progress progress = new Steering.Progress(0.3, 1500);

    private Walker(WalkOptions o, long now, PathBudget budget, PathCache cache) {
        this.o = o;
        this.deadline = now + o.timeoutMs();
        this.budget = budget;
        this.cache = cache;
    }

    /** Checks the distance, starts sprinting and begins planning. Fails with TOO_FAR before anything changes. */
    public static Walker start(BotBody b, WalkOptions o, PathBudget budget, PathCache cache, long now) {
        Position pos = b.position();
        if (o.usePath() && pos.horizontalDistance(o.target()) > MAX_DISTANCE) {
            throw new AgentError("TOO_FAR", "The target is more than " + MAX_DISTANCE + " blocks away",
                    "move_to a closer point first, or teleport the bot (server_command tp).");
        }
        Walker w = new Walker(o, now, budget, cache);
        if (o.sprint()) b.sprinting(true);
        if (o.usePath()) w.plan = PathPlan.begin(b, o, cache, now);
        return w;
    }

    public CompletableFuture<JsonObject> done() {
        return done;
    }

    public boolean sprint() {
        return o.sprint();
    }

    /** One tick of walking. Returns why the walk ended, or null while it goes on. */
    public String tick(BotBody b, long now) {
        if (now >= deadline) return "timeout";
        if (plan != null && !adopted) {
            if (!plan.advance(budget)) {
                b.input(MoveInput.stop(b.yaw()));
                return null;
            }
            adopted = true;
            String why = adopt(b, now);
            if (why != null) return why;
        }
        Position pos = b.position();
        List<Pathfinder.Step> steps = steps();
        if (index >= steps.size()) return approach(b, pos, now);
        // Skip ahead when the bot already stands at a later step (it cut a corner, or fell further).
        for (int k = Math.min(steps.size() - 1, index + 3); k >= index; k--) {
            if (at(steps.get(k), pos, b)) {
                index = k + 1;
                doorTicks = 0;
                progress = new Steering.Progress(0.3, 1500);
                return index >= steps.size() ? approach(b, pos, now) : null;
            }
        }
        Pathfinder.Step s = steps.get(index);
        double cx = s.x() + 0.5;
        double cz = s.z() + 0.5;
        double d = Steering.horizontal(cx - pos.x(), cz - pos.z());
        boolean waiting = false;
        if (s.move() == Pathfinder.Move.DOOR && d < 2.5) {
            BlockPoint door = closedDoor(b.blocks(), s);
            if (door != null) {
                if (doorTicks == 0) b.useBlock(door);
                if (++doorTicks > DOOR_WAIT_TICKS) return "blocked";
                waiting = true;
            }
        }
        if (!waiting && progress.stuck(d + Math.abs(s.standY() - pos.y()), now)) {
            if (++replans > MAX_REPLANS) return "stuck";
            return replan(b, now);
        }
        steer(b, cx, cz, s.standY(), waiting);
        return null;
    }

    /** Stops the keys, stops sprinting and completes the future with where the bot ended up and why. */
    public void finish(BotBody b, String reason) {
        b.input(MoveInput.stop(b.yaw()));
        if (o.sprint()) b.sprinting(false);
        Position pos = b.position();
        JsonObject r = new JsonObject();
        r.addProperty("reached", reason.equals("arrived"));
        r.addProperty("reason", reason);
        r.addProperty("x", Jsons.round(pos.x()));
        r.addProperty("y", Jsons.round(pos.y()));
        r.addProperty("z", Jsons.round(pos.z()));
        r.addProperty("distance", Jsons.round(pos.horizontalDistance(o.target())));
        if (o.usePath()) trace.describe(r, Math.min(replans, MAX_REPLANS));
        done.complete(r);
    }

    private List<Pathfinder.Step> steps() {
        return plan == null || !adopted ? List.of() : plan.steps();
    }

    /** Starts a new search from where the bot stands; the next tick takes it up. */
    private String replan(BotBody b, long now) {
        plan = PathPlan.begin(b, o, cache, now);
        adopted = false;
        return null;
    }

    /** Takes the finished search: why the walk cannot go on, or null. */
    private String adopt(BotBody b, long now) {
        index = 1;   // step 0 is where the bot stands
        progress = new Steering.Progress(0.3, 1500);
        String why = plan.verdict(o.target(), o.partial());
        trace.record(plan.result());
        plan.store(cache, b.world(), now);
        return why;
    }

    private boolean at(Pathfinder.Step s, Position pos, BotBody b) {
        double d = Steering.horizontal(s.x() + 0.5 - pos.x(), s.z() + 0.5 - pos.z());
        double dy = pos.y() - s.standY();
        if (s.move() == Pathfinder.Move.CLIMB) return d < 0.6 && Math.abs(dy) < 0.4;
        return d < 0.4 && dy > -0.5 && dy < 1.0 && (b.onGround() || b.inWater() || b.onClimbable());
    }

    /** The last stretch: from the final block to the exact target point, like the straight-line walk. */
    private String approach(BotBody b, Position pos, long now) {
        if (o.usePath() && !plan.complete()) {
            if (plan.endReason() != null) return plan.endReason();
            if (++stretches > MAX_STRETCHES) return "stuck";
            return replan(b, now);
        }
        double distance = pos.horizontalDistance(o.target());
        if (distance <= o.tolerance()) {
            // Over or under a target that is not at ground level, walking cannot get any closer.
            return Math.abs(o.target().y() - pos.y()) <= 1.5 ? "arrived" : "height";
        }
        if (progress.stuck(distance, now)) return "stuck";
        steer(b, o.target().x(), o.target().z(), o.target().y(), false);
        return null;
    }

    private void steer(BotBody b, double tx, double tz, double standY, boolean waiting) {
        Position pos = b.position();
        float yaw = Steering.yaw(tx - pos.x(), tz - pos.z());
        boolean ladder = b.onClimbable();
        boolean descending = ladder && standY < pos.y() - 0.2;
        float speed = b.sneaking() ? 0.3f : 1.0f;
        boolean jump;
        if (b.inWater()) jump = standY >= pos.y() - 0.2;
        else if (ladder) jump = standY > pos.y() + 0.2;
        else jump = b.onGround() && (standY - pos.y() > 0.6 || b.horizontalCollision());
        b.input(new MoveInput(yaw, waiting || descending ? 0f : speed, jump && !waiting, o.sprint() && !waiting && !b.sneaking()));
    }

    /** The closed door or gate at a DOOR step (its lower block), or null once it is open. */
    private static BlockPoint closedDoor(BlockView view, Pathfinder.Step s) {
        if (view.cell(s.x(), s.y(), s.z()).kind() == Cell.Kind.DOOR) return new BlockPoint(s.x(), s.y(), s.z());
        if (view.cell(s.x(), s.y() + 1, s.z()).kind() == Cell.Kind.DOOR) return new BlockPoint(s.x(), s.y() + 1, s.z());
        return null;
    }
}
