package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Builds behaviors by type for bot_behavior. The built-in types are always there; an agent adds its own (the
 * script behavior needs the agent's script engine). Thread-safe after setup.
 */
public final class BehaviorFactory {
    /** Builds one behavior instance for one bot. */
    @FunctionalInterface
    public interface Maker {
        Behavior make(JsonObject params, String botName, SplittableRandom random);
    }

    private final Map<String, Maker> makers = new LinkedHashMap<>();

    public BehaviorFactory() {
        makers.put("wander", (p, n, r) -> Wander.parse(p, r));
        makers.put("follow", (p, n, r) -> Follow.parse(p));
        makers.put("patrol", (p, n, r) -> Patrol.parse(p));
        makers.put("chat", (p, n, r) -> MessageLoop.parse(p, false, r));
        makers.put("command_loop", (p, n, r) -> MessageLoop.parse(p, true, r));
        makers.put("menu_click", (p, n, r) -> MenuClick.parse(p, r));
        makers.put("idle", (p, n, r) -> Idle.parse(p, r));
    }

    public BehaviorFactory register(String type, Maker maker) {
        makers.put(type, maker);
        return this;
    }

    /** One behavior for `botName`; `seed` makes the random choices repeatable across runs. */
    public Behavior create(String type, JsonObject params, String botName, long seed) {
        Maker m = makers.get(type);
        if (m == null) throw BotErrors.invalid("Unknown behavior " + type + "; one of " + String.join(", ", makers.keySet()));
        return m.make(params, botName, Cadence.randomFor(botName, seed));
    }

    /** Fails with INVALID_PARAMS now, before any bot gets a behavior, when the parameters are wrong. */
    public void check(String type, JsonObject params) {
        create(type, params, "Check", 0);
    }
}
