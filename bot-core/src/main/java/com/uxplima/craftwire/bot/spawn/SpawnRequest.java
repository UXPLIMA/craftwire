package com.uxplima.craftwire.bot.spawn;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.model.ClientSettings;
import com.uxplima.craftwire.bot.model.Profile;
import com.uxplima.craftwire.bot.resourcepack.PackPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A bot.spawn request, the part every kind shares. Kind-specific fields (location for plugin bots, address,
 * version and auth for protocol bots) stay in {@link #raw}.
 *
 * @param names       explicit names, or empty to allocate {@code count} names from {@code prefix}
 * @param perTick     how many bots join per server tick (or per 50 ms in the bot runner)
 * @param spreadRadius 0, or a radius around the location in which each bot gets a random surface point
 * @param detail      return one summary per bot instead of the compact name range
 */
public record SpawnRequest(List<String> names, int count, String prefix, String group, Profile profile, int perTick, int spreadRadius,
                           ClientSettings settings, PackPolicy resourcePack, boolean verifyPacks, boolean hideBots, boolean detail, long seed,
                           JsonObject raw) {
    public static final int MAX_BOTS = 1000;
    private static final Pattern GROUP = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    public static SpawnRequest parse(JsonObject p) {
        List<String> names = new ArrayList<>();
        if (p.has("names")) {
            for (JsonElement e : Params.array(p, "names")) names.add(BotNames.validate(e.getAsString()));
            if (names.isEmpty() || names.size() > MAX_BOTS) throw BotErrors.invalid("names takes 1-" + MAX_BOTS + " names");
            long distinct = names.stream().map(s -> s.toLowerCase(Locale.ROOT)).distinct().count();
            if (distinct != names.size()) throw BotErrors.invalid("names has duplicates");
        }
        String group = Params.optString(p, "group").orElse(null);
        if (group != null && !GROUP.matcher(group).matches()) throw BotErrors.invalid("group is 1-32 letters, digits, _ or -");
        int spread = Params.optObject(p, "spread").map(s -> Params.clampedInt(s, "radius", 16, 1, 10_000)).orElse(0);
        return new SpawnRequest(List.copyOf(names),
                names.isEmpty() ? Params.clampedInt(p, "count", 1, 1, MAX_BOTS) : names.size(),
                Params.optString(p, "namePrefix").orElse("Bot"),
                group,
                Profile.parse(Params.optString(p, "profile").orElse(null)),
                Params.clampedInt(p, "perTick", 5, 1, 100),
                spread,
                ClientSettings.parse(Params.optObject(p, "settings").orElse(null)),
                PackPolicy.parse(Params.optString(p, "resourcePack").orElse(null)),
                Params.bool(p, "verifyPacks", true),
                Params.bool(p, "hideBots", false),
                Params.bool(p, "detail", false),
                Params.optLong(p, "seed").orElse(System.nanoTime()),
                p);
    }

    /** The names to join: the explicit ones, or `count` free names from the prefix. */
    public List<String> resolveNames(Predicate<String> taken) {
        return names.isEmpty() ? BotNames.allocate(prefix, count, taken) : names;
    }

    /** How long joining all of them takes at `perTick`, so callers can size their timeouts. */
    public long expectedMs() {
        return (long) Math.ceil(count / (double) perTick) * 50L;
    }
}
