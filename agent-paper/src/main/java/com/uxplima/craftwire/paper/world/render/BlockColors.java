package com.uxplima.craftwire.paper.world.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Average top and side colours per block id from the game's textures; the hub sends them with iso renders. */
public final class BlockColors {
    private static final Map<String, int[]> TABLE = new ConcurrentHashMap<>();

    private BlockColors() {}

    public static void set(JsonObject table) {
        for (Map.Entry<String, JsonElement> e : table.entrySet()) {
            var a = e.getValue().getAsJsonArray();
            TABLE.put(e.getKey(), new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()});
        }
    }

    public static boolean loaded() {
        return !TABLE.isEmpty();
    }

    public static int top(String id) {
        int[] c = TABLE.get(id);
        return c == null ? -1 : c[0];
    }

    public static int side(String id) {
        int[] c = TABLE.get(id);
        return c == null ? -1 : c[1];
    }
}
