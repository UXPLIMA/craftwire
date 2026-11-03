package com.uxplima.craftwire.botrunner.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * The runner's shared worlds (world mode `shared`): bots joining the same server and dimension get the same
 * {@link SharedWorld}; it is dropped when its last bot leaves. Bots in world mode `none` never enter one. Runner loop
 * thread.
 */
public final class WorldCache {
    private final Map<WorldKey, SharedWorld> worlds = new HashMap<>();

    /** The world for this key, created with `classifier` by the first bot that enters it. */
    public SharedWorld enter(WorldKey key, BlockClassifier classifier) {
        SharedWorld w = worlds.computeIfAbsent(key, k -> new SharedWorld(k, classifier));
        w.attach();
        return w;
    }

    /** A bot left (changed dimension, disconnected); its chunks and entities are let go. */
    public void leave(SharedWorld world, Object holder) {
        if (world.detach(holder) <= 0) worlds.remove(world.key());
    }

    /** For bot_list and load reports: how much the cache holds. */
    public JsonObject stats() {
        JsonArray list = new JsonArray();
        long bytes = 0;
        for (SharedWorld w : worlds.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("server", w.key().server());
            o.addProperty("dimension", w.key().dimension());
            o.addProperty("chunks", w.chunkCount());
            o.addProperty("entities", w.entities().size());
            long b = w.bytes();
            o.addProperty("mb", Math.round(b / 1048576.0 * 10) / 10.0);
            bytes += b;
            list.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("worlds", list);
        r.addProperty("mb", Math.round(bytes / 1048576.0 * 10) / 10.0);
        return r;
    }
}
