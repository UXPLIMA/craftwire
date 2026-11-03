package com.uxplima.craftwire.bot.move;

import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.path.PathBudget;
import com.uxplima.craftwire.bot.path.PathCache;
import com.uxplima.craftwire.bot.path.PathSearch;
import com.uxplima.craftwire.bot.path.Pathfinder;
import java.util.List;

/** One path search of a walk: taken from the cache, or run in budgeted slices until it ends. Bot thread only. */
final class PathPlan {
    private final BlockPoint start;
    private final BlockPoint goal;
    private final Pathfinder.Options options;
    private PathSearch search;
    private Pathfinder.Result result;
    private List<Pathfinder.Step> steps = List.of();
    private String endReason;

    private PathPlan(BlockPoint start, BlockPoint goal, Pathfinder.Options options) {
        this.start = start;
        this.goal = goal;
        this.options = options;
    }

    static PathPlan begin(BotBody b, WalkOptions o, PathCache cache, long now) {
        BlockPoint s = b.position().block();
        BlockPoint g = o.target().block();
        PathPlan p = new PathPlan(s, g, o.path());
        Pathfinder.Result cached = cache.get(b.world(), s.x(), s.y(), s.z(), g.x(), g.y(), g.z(), o.path(), now);
        if (cached != null) {
            p.result = cached;
            p.steps = cached.steps();
        } else {
            p.search = Pathfinder.start(b.blocks(), s.x(), s.y(), s.z(), g.x(), g.y(), g.z(), o.path());
        }
        return p;
    }

    /** Runs one budgeted slice; true once the search has ended (at once for a cached path). */
    boolean advance(PathBudget budget) {
        if (result != null) return true;
        if (!budget.slice(search)) return false;
        result = search.result();
        steps = result.steps();
        search = null;
        return true;
    }

    Pathfinder.Result result() {
        return result;
    }

    List<Pathfinder.Step> steps() {
        return steps;
    }

    boolean complete() {
        return result != null && result.complete();
    }

    /** Why the walk ends at the end of an incomplete path (height, no_path); null when it plans again there. */
    String endReason() {
        return endReason;
    }

    /** Decides what an incomplete result means. Returns why the walk cannot start, or null. */
    String verdict(Position target, boolean partial) {
        if (result.complete()) return null;
        boolean moves = result.steps().size() > 1;
        Pathfinder.Step closest = result.closest();
        BlockPoint t = target.block();
        if (closest.x() == t.x() && closest.z() == t.z()) {
            endReason = "height";   // right below or above the target: walking cannot get closer
        } else if (result.unloaded() && moves) {
            endReason = null;       // the way goes on into chunks that load as the bot walks: plan again there
        } else if (partial && moves) {
            endReason = "no_path";
        } else {
            steps = List.of();
            return "no_path";
        }
        return null;
    }

    void store(PathCache cache, String world, long now) {
        cache.put(world, start.x(), start.y(), start.z(), goal.x(), goal.y(), goal.z(), options, result, now);
    }
}
