package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.events.EventTap;
import java.util.ArrayList;
import java.util.List;

/** events: what fired (summary/query), who listens (listeners), and opting into busy events (watch). */
final class EventsHandler {
    private EventsHandler() {}

    static JsonElement handle(JsonObject p, CraftwirePlugin plugin) {
        EventTap tap = plugin.events();
        if (tap == null) {
            throw new AgentError("PERMISSION_DISABLED", "record-events is disabled in plugins/Craftwire/config.yml",
                    "Ask the server owner to set record-events: true and restart the server.");
        }
        long since = Args.optLong(p, "since").orElse(0L);
        return switch (Args.string(p, "action")) {
            case "summary" -> tap.summary(since);
            case "query" -> tap.query(Args.optString(p, "type").orElse(null), Args.optString(p, "player").orElse(null), since,
                    Math.clamp(Args.optInt(p, "limit").orElse(50), 1, 500), Args.bool(p, "cancelledOnly", false));
            case "listeners" -> tap.listeners(Args.string(p, "type"));
            case "watch" -> {
                List<String> types = new ArrayList<>();
                if (!p.has("types") || !p.get("types").isJsonArray()) throw Args.invalid("watch needs types: [event names]");
                p.getAsJsonArray("types").forEach(t -> types.add(t.getAsString()));
                yield tap.watch(types);
            }
            default -> throw Args.invalid("action must be summary, query, listeners or watch");
        };
    }
}
