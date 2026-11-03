package com.uxplima.craftwire.bot.model;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Jsons;

/** A point in a world, in blocks. */
public record Position(double x, double y, double z) {
    public double distance(Position o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double horizontalDistance(Position o) {
        return Math.hypot(x - o.x, z - o.z);
    }

    /** The block the feet are in; a hair above y so standing exactly on a block top counts as above it. */
    public BlockPoint block() {
        return new BlockPoint((int) Math.floor(x), (int) Math.floor(y + 1e-3), (int) Math.floor(z));
    }

    public Position add(double dx, double dy, double dz) {
        return new Position(x + dx, y + dy, z + dz);
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("x", Jsons.round(x));
        o.addProperty("y", Jsons.round(y));
        o.addProperty("z", Jsons.round(z));
        return o;
    }
}
