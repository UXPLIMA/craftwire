package com.uxplima.craftwire.bot.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collection;

/** Small JSON helpers for results. */
public final class Jsons {
    private Jsons() {}

    /** Two decimals: enough for positions and rotations, and shorter results for the model. */
    public static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    public static JsonArray strings(Collection<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    public static JsonObject of(String key, boolean value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    public static JsonObject of(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    public static JsonObject of(String key, Number value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }
}
