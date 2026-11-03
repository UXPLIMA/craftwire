package com.uxplima.craftwire.bot.move;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.path.Pathfinder;

/**
 * One move_to request.
 *
 * @param usePath  follow a path from the pathfinder; false walks in a straight line
 * @param partial  walk as close as possible when no complete path exists, instead of failing with no_path
 */
public record WalkOptions(Position target, double tolerance, boolean sprint, long timeoutMs, boolean usePath, Pathfinder.Options path,
                          boolean partial) {
    /** The old single-search limit; with the budget a search this big spreads over a few ticks. */
    public static final int MAX_NODES = 40_000;

    /** move_to parameters: x, y, z, tolerance, sprint, timeoutMs, path, maxFall, openDoors, partial. */
    public static WalkOptions parse(JsonObject p) {
        return new WalkOptions(new Position(Params.number(p, "x"), Params.number(p, "y"), Params.number(p, "z")),
                Params.optNumber(p, "tolerance").orElse(0.5),
                Params.bool(p, "sprint", false),
                Params.clampedLong(p, "timeoutMs", 30_000L, 500L, 120_000L),
                Params.bool(p, "path", true),
                new Pathfinder.Options(Params.clampedInt(p, "maxFall", 3, 0, 10), Params.bool(p, "openDoors", true), MAX_NODES),
                Params.bool(p, "partial", false));
    }

    /** A behavior's walk: pathfinding, partial allowed, no sprint unless asked. */
    public static WalkOptions to(Position target, boolean sprint, long timeoutMs) {
        return new WalkOptions(target, 1.0, sprint, timeoutMs, true, new Pathfinder.Options(3, true, MAX_NODES), true);
    }
}
