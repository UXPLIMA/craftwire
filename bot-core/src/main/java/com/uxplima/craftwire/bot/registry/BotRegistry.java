package com.uxplima.craftwire.bot.registry;

import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.select.BotSelector;
import com.uxplima.craftwire.bot.spawn.NameRanges;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The bots of one agent by name, plus the names being joined right now so two spawns cannot take one name.
 * Thread-safe: spawns, removals and lookups come from the hub connection, ticks from the bots' own threads.
 *
 * @param <B> the agent's bot type
 */
public final class BotRegistry<B> {
    private final Map<String, B> bots = new ConcurrentHashMap<>();
    private final Set<String> joining = ConcurrentHashMap.newKeySet();
    private final Function<B, String> name;
    private final Function<B, String> group;

    public BotRegistry(Function<B, String> name, Function<B, String> group) {
        this.name = name;
        this.group = group;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Claims a name for a join; false when a bot has it or another join claimed it. */
    public boolean claim(String n) {
        String k = key(n);
        return !bots.containsKey(k) && joining.add(k);
    }

    public void release(String n) {
        joining.remove(key(n));
    }

    /** A claimed name joined: it is now a bot. */
    public void joined(B bot) {
        String k = key(name.apply(bot));
        bots.put(k, bot);
        joining.remove(k);
    }

    public boolean taken(String n) {
        String k = key(n);
        return bots.containsKey(k) || joining.contains(k);
    }

    public B get(String n) {
        B b = bots.get(key(n));
        if (b == null) throw BotErrors.notFound(n);
        return b;
    }

    public B find(String n) {
        return bots.get(key(n));
    }

    /** Removes the entry only while it still maps to this bot (a rejoin under the same name keeps its entry). */
    public void remove(B bot) {
        bots.remove(key(name.apply(bot)), bot);
    }

    public Collection<B> all() {
        return List.copyOf(bots.values());
    }

    public int size() {
        return bots.size();
    }

    /** The bots a selector names; a single exact name that is not a bot fails with BOT_NOT_FOUND. */
    public List<B> select(BotSelector s) {
        if (s.single() && s.group() == null) return List.of(get(s.names().getFirst()));
        List<B> out = new ArrayList<>();
        for (B b : bots.values()) if (s.matches(name.apply(b), group.apply(b))) out.add(b);
        out.sort((a, c) -> NameRanges.natural(name.apply(a), name.apply(c)));
        return out;
    }

    public void clear() {
        bots.clear();
        joining.clear();
    }
}
