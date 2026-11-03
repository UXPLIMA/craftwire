package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.WalkOptions;
import java.util.SplittableRandom;

/** Walks to random points within `radius` of where it started (or `center`), pausing between walks. */
final class Wander implements Behavior {
    private final int radius;
    private final Position center;
    private final boolean sprint;
    private final Cadence pause;
    private final SplittableRandom random;
    private Position home;
    private int walks;

    private Wander(int radius, Position center, boolean sprint, Cadence pause, SplittableRandom random) {
        this.radius = radius;
        this.center = center;
        this.sprint = sprint;
        this.pause = pause;
        this.random = random;
    }

    /** radius (16), center {x,y,z}?, sprint, intervalMs: the pause between walks (3000). */
    static Wander parse(JsonObject p, SplittableRandom random) {
        Position c = Params.optObject(p, "center")
                .map(o -> new Position(Params.number(o, "x"), Params.number(o, "y"), Params.number(o, "z"))).orElse(null);
        return new Wander(Params.clampedInt(p, "radius", 16, 1, 128), c, Params.bool(p, "sprint", false), Cadence.parse(p, 3000, random), random);
    }

    @Override
    public String type() {
        return "wander";
    }

    @Override
    public void tick(BotControl bot, long now) {
        if (home == null) home = center != null ? center : bot.position();
        if (!bot.alive() || bot.moving() || !pause.due(now)) return;
        double angle = random.nextDouble(Math.PI * 2);
        double dist = random.nextDouble(1, radius + 1);
        Position target = new Position(home.x() + Math.cos(angle) * dist, bot.position().y(), home.z() + Math.sin(angle) * dist);
        walks++;
        bot.moveTo(WalkOptions.to(target, sprint, 20_000));
    }

    @Override
    public JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type());
        o.addProperty("radius", radius);
        if (home != null) o.add("center", home.json());
        o.addProperty("walks", walks);
        return o;
    }
}
