package com.uxplima.craftwire.bot.model;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;

/** A block position. */
public record BlockPoint(int x, int y, int z) {
    /** The centre of the block's bottom face: where a player stands on it. */
    public Position feet() {
        return new Position(x + 0.5, y, z + 0.5);
    }

    public Position center() {
        return new Position(x + 0.5, y + 0.5, z + 0.5);
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    /** {x, y, z} integers. */
    public static BlockPoint parse(JsonObject o) {
        return new BlockPoint(Params.integer(o, "x"), Params.integer(o, "y"), Params.integer(o, "z"));
    }
}
