package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import java.util.SplittableRandom;

/**
 * Stands where it is like an AFK player: now and then looks around and, with `swing`, swings its arm. Anti-AFK
 * plugins see rotation changes and arm swings.
 */
final class Idle implements Behavior {
    private final boolean look;
    private final boolean swing;
    private final Cadence cadence;
    private final SplittableRandom random;

    private Idle(boolean look, boolean swing, Cadence cadence, SplittableRandom random) {
        this.look = look;
        this.swing = swing;
        this.cadence = cadence;
        this.random = random;
    }

    /** look (true), swing (false), intervalMs (3000). */
    static Idle parse(JsonObject p, SplittableRandom random) {
        return new Idle(Params.bool(p, "look", true), Params.bool(p, "swing", false), Cadence.parse(p, 3000, random), random);
    }

    @Override
    public String type() {
        return "idle";
    }

    @Override
    public void tick(BotControl bot, long now) {
        if (!bot.alive() || !cadence.due(now)) return;
        if (look) bot.look((float) random.nextDouble(-180, 180), (float) random.nextDouble(-30, 30));
        if (swing) bot.swing();
    }

    @Override
    public JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type());
        o.addProperty("look", look);
        o.addProperty("swing", swing);
        return o;
    }
}
