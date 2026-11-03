package com.uxplima.craftwire.bot.move;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Jsons;
import com.uxplima.craftwire.bot.path.Pathfinder;
import java.util.List;

/** What a move_to result says about its path: the last plan's size, length and completeness, and the closest point. */
final class WalkTrace {
    private Pathfinder.Result last;

    void record(Pathfinder.Result r) {
        last = r;
    }

    void describe(JsonObject r, int replans) {
        if (last == null) return;
        List<Pathfinder.Step> steps = last.steps();
        JsonObject path = new JsonObject();
        path.addProperty("nodes", steps.size());
        double length = 0;
        for (int i = 1; i < steps.size(); i++) {
            Pathfinder.Step a = steps.get(i - 1);
            Pathfinder.Step c = steps.get(i);
            length += Math.sqrt(Math.pow(c.x() - a.x(), 2) + Math.pow(c.standY() - a.standY(), 2) + Math.pow(c.z() - a.z(), 2));
        }
        path.addProperty("length", Jsons.round(length));
        path.addProperty("complete", last.complete());
        path.addProperty("explored", last.explored());
        r.add("path", path);
        r.addProperty("replans", replans);
        if (!last.complete() && last.closest() != null) {
            JsonObject c = new JsonObject();
            c.addProperty("x", last.closest().x());
            c.addProperty("y", Jsons.round(last.closest().standY()));
            c.addProperty("z", last.closest().z());
            r.add("closest", c);
        }
    }
}
