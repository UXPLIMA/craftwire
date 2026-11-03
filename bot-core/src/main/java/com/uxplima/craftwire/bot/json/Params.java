package com.uxplima.craftwire.bot.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Reads request parameters without any server API; a missing or mistyped required value is INVALID_PARAMS. */
public final class Params {
    private Params() {}

    public static Optional<JsonElement> get(JsonObject p, String k) {
        JsonElement e = p.get(k);
        return e == null || e.isJsonNull() ? Optional.empty() : Optional.of(e);
    }

    public static Optional<String> optString(JsonObject p, String k) {
        return get(p, k).map(e -> read(k, e::getAsString));
    }

    public static Optional<Integer> optInt(JsonObject p, String k) {
        return get(p, k).map(e -> read(k, e::getAsInt));
    }

    public static Optional<Long> optLong(JsonObject p, String k) {
        return get(p, k).map(e -> read(k, e::getAsLong));
    }

    public static Optional<Double> optNumber(JsonObject p, String k) {
        return get(p, k).map(e -> read(k, e::getAsDouble));
    }

    public static boolean bool(JsonObject p, String k, boolean fallback) {
        return get(p, k).map(e -> read(k, e::getAsBoolean)).orElse(fallback);
    }

    public static String string(JsonObject p, String k) {
        return optString(p, k).orElseThrow(() -> missing(k));
    }

    public static int integer(JsonObject p, String k) {
        return optInt(p, k).orElseThrow(() -> missing(k));
    }

    public static double number(JsonObject p, String k) {
        return optNumber(p, k).orElseThrow(() -> missing(k));
    }

    /** An int clamped to [min, max], or the fallback when absent. */
    public static int clampedInt(JsonObject p, String k, int fallback, int min, int max) {
        return Math.clamp(optInt(p, k).orElse(fallback), min, max);
    }

    public static long clampedLong(JsonObject p, String k, long fallback, long min, long max) {
        return Math.clamp(optLong(p, k).orElse(fallback), min, max);
    }

    public static JsonObject object(JsonObject p, String k) {
        return optObject(p, k).orElseThrow(() -> missing(k));
    }

    public static Optional<JsonObject> optObject(JsonObject p, String k) {
        return get(p, k).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject);
    }

    public static JsonArray array(JsonObject p, String k) {
        return get(p, k).filter(JsonElement::isJsonArray).map(JsonElement::getAsJsonArray).orElseThrow(() -> missing(k));
    }

    public static List<String> strings(JsonObject p, String k) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array(p, k)) out.add(read(k, e::getAsString));
        return out;
    }

    public static List<Integer> ints(JsonObject p, String k) {
        List<Integer> out = new ArrayList<>();
        for (JsonElement e : array(p, k)) out.add(read(k, e::getAsInt));
        return out;
    }

    private static <T> T read(String k, Supplier<T> value) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            throw BotErrors.invalid("`" + k + "` has the wrong type");
        }
    }

    private static AgentError missing(String k) {
        return BotErrors.invalid("`" + k + "` is required");
    }
}
