package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.WalkOptions;
import java.util.ArrayList;
import java.util.List;

/** Walks a list of points in order, pausing at each; loops back to the first unless `loop` is false. */
final class Patrol implements Behavior {
    private final List<Position> points;
    private final boolean loop;
    private final long pauseMs;
    private final boolean sprint;
    private int next;
    private int laps;
    private long resumeAt;
    private boolean walking;
    private boolean finished;

    private Patrol(List<Position> points, boolean loop, long pauseMs, boolean sprint) {
        this.points = points;
        this.loop = loop;
        this.pauseMs = pauseMs;
        this.sprint = sprint;
    }

    /** points [{x,y,z}, …] (2-64), loop (true), pauseMs (0), sprint. */
    static Patrol parse(JsonObject p) {
        List<Position> points = new ArrayList<>();
        for (JsonElement e : Params.array(p, "points")) {
            if (!e.isJsonObject()) throw BotErrors.invalid("points are {x, y, z} objects");
            JsonObject o = e.getAsJsonObject();
            points.add(new Position(Params.number(o, "x"), Params.number(o, "y"), Params.number(o, "z")));
        }
        if (points.size() < 2 || points.size() > 64) throw BotErrors.invalid("patrol takes 2-64 points");
        return new Patrol(List.copyOf(points), Params.bool(p, "loop", true), Params.clampedLong(p, "pauseMs", 0L, 0L, 600_000L),
                Params.bool(p, "sprint", false));
    }

    @Override
    public String type() {
        return "patrol";
    }

    @Override
    public void tick(BotControl bot, long now) {
        if (finished || !bot.alive() || bot.moving()) return;
        if (walking) {
            // Just arrived (or gave up on) a point: the pause starts now.
            walking = false;
            resumeAt = now + pauseMs;
        }
        if (now < resumeAt) return;
        bot.moveTo(WalkOptions.to(points.get(next), sprint, 60_000));
        walking = true;
        next++;
        if (next == points.size()) {
            laps++;
            next = 0;
            if (!loop) finished = true;
        }
    }

    @Override
    public JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type());
        o.addProperty("points", points.size());
        o.addProperty("next", next);
        o.addProperty("laps", laps);
        o.addProperty("finished", finished);
        return o;
    }
}
