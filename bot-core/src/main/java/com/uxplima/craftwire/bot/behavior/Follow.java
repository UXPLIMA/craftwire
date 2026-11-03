package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.WalkOptions;

/** Keeps within `distance` blocks of a player, walking again whenever the player moved away from the last goal. */
final class Follow implements Behavior {
    /** Re-checks twice a second: path searches for a moving target otherwise run every tick. */
    private static final long CHECK_MS = 500;

    private final String target;
    private final double distance;
    private final boolean sprint;
    private Position goal;
    private long nextCheck;
    private boolean visible;

    private Follow(String target, double distance, boolean sprint) {
        this.target = target;
        this.distance = distance;
        this.sprint = sprint;
    }

    /** target (a player name), distance (3), sprint. */
    static Follow parse(JsonObject p) {
        return new Follow(Params.string(p, "target"), Math.clamp(Params.optNumber(p, "distance").orElse(3.0), 1.0, 32.0),
                Params.bool(p, "sprint", false));
    }

    @Override
    public String type() {
        return "follow";
    }

    @Override
    public void tick(BotControl bot, long now) {
        if (now < nextCheck || !bot.alive()) return;
        nextCheck = now + CHECK_MS;
        Position at = bot.playerPosition(target).orElse(null);
        visible = at != null;
        if (at == null) return;
        Position me = bot.position();
        if (me.horizontalDistance(at) <= distance + 0.5) {
            if (bot.moving()) bot.stopMoving();
            goal = null;
            return;
        }
        if (bot.moving() && goal != null && goal.horizontalDistance(at) < 2) return;
        goal = at;
        WalkOptions o = WalkOptions.to(at, sprint, 15_000);
        bot.moveTo(new WalkOptions(o.target(), distance, o.sprint(), o.timeoutMs(), true, o.path(), true));
    }

    @Override
    public JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type());
        o.addProperty("target", target);
        o.addProperty("distance", distance);
        o.addProperty("targetVisible", visible);
        return o;
    }
}
