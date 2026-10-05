package com.uxplima.craftwire.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** Reads request parameters; a missing required value is INVALID_PARAMS. */
public final class Args {
    private Args() {}

    private static Optional<JsonElement> get(JsonObject p, String k) {
        JsonElement e = p.get(k);
        return e == null || e.isJsonNull() ? Optional.empty() : Optional.of(e);
    }

    public static Optional<String> optString(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsString);
    }

    public static Optional<Integer> optInt(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsInt);
    }

    public static Optional<Long> optLong(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsLong);
    }

    public static boolean bool(JsonObject p, String k, boolean fallback) {
        return get(p, k).map(JsonElement::getAsBoolean).orElse(fallback);
    }

    public static String string(JsonObject p, String k) {
        return optString(p, k).orElseThrow(() -> missing(k));
    }

    public static int integer(JsonObject p, String k) {
        return optInt(p, k).orElseThrow(() -> missing(k));
    }

    public static double number(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsDouble).orElseThrow(() -> missing(k));
    }

    public static JsonObject object(JsonObject p, String k) {
        return get(p, k).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElseThrow(() -> missing(k));
    }

    public static JsonArray array(JsonObject p, String k) {
        return get(p, k).filter(JsonElement::isJsonArray).map(JsonElement::getAsJsonArray).orElseThrow(() -> missing(k));
    }

    /** The world named by {@code world}, or the main world when absent. */
    public static World world(JsonObject p) {
        Optional<String> name = optString(p, "world");
        if (name.isEmpty()) return Bukkit.getWorlds().get(0);
        World w = Bukkit.getWorld(name.get());
        if (w == null) {
            throw new AgentError("WORLD_NOT_FOUND", "No world named " + name.get(),
                    "Loaded worlds: " + Bukkit.getWorlds().stream().map(World::getName).toList() + ".");
        }
        return w;
    }

    public static AgentError invalid(String message) {
        return new AgentError("INVALID_PARAMS", message, "Check the tool's parameter description and retry.");
    }

    private static AgentError missing(String k) {
        return invalid("`" + k + "` is required");
    }
}
