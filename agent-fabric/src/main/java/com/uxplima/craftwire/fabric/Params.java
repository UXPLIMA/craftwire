package com.uxplima.craftwire.fabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.util.Optional;

public final class Params {
    private Params() {}

    private static Optional<JsonElement> get(JsonObject p, String k) {
        JsonElement e = p.get(k);
        return e == null || e.isJsonNull() ? Optional.empty() : Optional.of(e);
    }

    public static Optional<Integer> optInt(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsInt);
    }

    public static Optional<Double> optDouble(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsDouble);
    }

    public static Optional<String> optString(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsString);
    }

    public static Optional<Boolean> optBool(JsonObject p, String k) {
        return get(p, k).map(JsonElement::getAsBoolean);
    }

    public static AgentError notInWorld() {
        return new AgentError("NOT_IN_WORLD", "The player is not in a world.", "Join a world or server first (the title screen has no player).");
    }

    public static AgentError invalid(String message) {
        return new AgentError("INVALID_PARAMS", message, "Check the tool's parameter description and retry.");
    }
}
