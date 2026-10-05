package com.uxplima.craftwire.paper.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.graalvm.polyglot.Value;

/** Script results as JSON: JS values structurally, Java objects as {class, toString}. */
public final class ValueJson {
    static final int MAX_DEPTH = 4;
    static final int MAX_ITEMS = 1000;
    static final int MAX_TEXT = 1000;

    private ValueJson() {}

    public static JsonElement toJson(Value v) {
        return convert(v, 0);
    }

    private static JsonElement convert(Value v, int depth) {
        if (v == null || v.isNull()) return JsonNull.INSTANCE;
        if (v.isBoolean()) return new JsonPrimitive(v.asBoolean());
        if (v.isNumber()) {
            if (v.fitsInLong()) return new JsonPrimitive(v.asLong());
            double d = v.asDouble();
            return Double.isFinite(d) ? new JsonPrimitive(d) : new JsonPrimitive(String.valueOf(d));
        }
        if (v.isString()) return new JsonPrimitive(v.asString());
        if (depth >= MAX_DEPTH) return new JsonPrimitive(cut(v.toString()));
        if (v.hasArrayElements()) {
            JsonArray a = new JsonArray();
            long n = Math.min(v.getArraySize(), MAX_ITEMS);
            for (long i = 0; i < n; i++) a.add(convert(v.getArrayElement(i), depth + 1));
            return a;
        }
        if (v.isHostObject()) {
            JsonObject o = new JsonObject();
            o.addProperty("class", v.asHostObject().getClass().getName());
            o.addProperty("toString", cut(v.toString()));
            return o;
        }
        if (v.canExecute()) return new JsonPrimitive("[function]");
        if (v.hasMembers()) {
            JsonObject o = new JsonObject();
            int count = 0;
            for (String key : v.getMemberKeys()) {
                if (count++ == MAX_ITEMS) break;
                o.add(key, convert(v.getMember(key), depth + 1));
            }
            return o;
        }
        return new JsonPrimitive(cut(v.toString()));
    }

    private static String cut(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
    }
}
