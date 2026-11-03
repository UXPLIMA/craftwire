package com.uxplima.craftwire.bot.model;

import com.uxplima.craftwire.core.AgentError;
import java.util.Locale;

/**
 * How much of a real player a bot costs. Measured on Paper 26.2 (50 bots spread out): the server defaults cost
 * 32.6 ms per tick, the light distances 12.8 ms and falling, because the world each bot keeps loaded is the cost.
 */
public enum Profile {
    /** View 3, simulation 2, send 2, no mob spawning around the bot, no advancements or statistics. The default. */
    LIGHT,
    /** Server defaults for distances and spawning, advancements and statistics on: what a real player costs. */
    REALISTIC;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Profile parse(String id) {
        if (id == null) return LIGHT;
        for (Profile p : values()) if (p.id().equals(id)) return p;
        throw new AgentError("INVALID_PARAMS", "profile must be light or realistic, not " + id,
                "light (default) is cheap for load tests; realistic behaves like a real player.");
    }
}
