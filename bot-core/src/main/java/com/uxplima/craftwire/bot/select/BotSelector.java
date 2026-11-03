package com.uxplima.craftwire.bot.select;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which bots a request names: `bot` (one name), `bots` (a list of names, or one pattern such as "Load*"), or `group`
 * (the group given at spawn). Names match case-insensitively, as player names do.
 */
public record BotSelector(List<String> names, Pattern pattern, String group) {
    /** Reads bot, bots or group; at least one is required. */
    public static BotSelector parse(JsonObject p) {
        String group = Params.optString(p, "group").orElse(null);
        if (p.has("bot") && !p.get("bot").isJsonNull()) return new BotSelector(List.of(Params.string(p, "bot")), null, group);
        JsonElement bots = p.get("bots");
        if (bots != null && bots.isJsonPrimitive()) return new BotSelector(List.of(), glob(bots.getAsString()), group);
        if (bots != null && bots.isJsonArray()) return new BotSelector(Params.strings(p, "bots"), null, group);
        if (group != null) return new BotSelector(List.of(), null, group);
        throw BotErrors.invalid("Name the bots: bot (a name), bots (names, or a pattern like \"Load*\") or group");
    }

    /** One bot by name. */
    public static BotSelector one(String name) {
        return new BotSelector(List.of(name), null, null);
    }

    /** A single exact name, the way most actions address one bot. */
    public boolean single() {
        return names.size() == 1 && pattern == null;
    }

    public boolean matches(String name, String botGroup) {
        if (group != null && (botGroup == null || !botGroup.equalsIgnoreCase(group))) return false;
        if (pattern != null) return pattern.matcher(name.toLowerCase(Locale.ROOT)).matches();
        if (!names.isEmpty()) return names.stream().anyMatch(n -> n.equalsIgnoreCase(name));
        return group != null;
    }

    /** `*` any run of characters, `?` one character; everything else literal. */
    static Pattern glob(String g) {
        if (g.isEmpty() || g.length() > 32) throw BotErrors.invalid("bots pattern must be 1-32 characters");
        List<String> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        for (char c : g.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '*' || c == '?') {
                if (!lit.isEmpty()) parts.add(Pattern.quote(lit.toString()));
                lit.setLength(0);
                parts.add(c == '*' ? ".*" : ".");
            } else {
                lit.append(c);
            }
        }
        if (!lit.isEmpty()) parts.add(Pattern.quote(lit.toString()));
        return Pattern.compile(String.join("", parts));
    }
}
