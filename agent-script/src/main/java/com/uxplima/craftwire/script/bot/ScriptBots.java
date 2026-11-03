package com.uxplima.craftwire.script.bot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

/**
 * The `bots` global of a bot script: register handlers with onTick(fn(bot, tick)) and onMessage(fn(bot, message)),
 * visit every bot with each(fn(bot)), print with log(text). Handlers run inside the script's lock.
 */
public final class ScriptBots {
    private final List<Value> tickHandlers = new CopyOnWriteArrayList<>();
    private final List<Value> messageHandlers = new CopyOnWriteArrayList<>();
    private final Map<String, ScriptBot> bots = new ConcurrentHashMap<>();
    private final StringBuilder log = new StringBuilder();

    @HostAccess.Export
    public void onTick(Value fn) {
        requireFunction(fn);
        tickHandlers.add(fn);
    }

    @HostAccess.Export
    public void onMessage(Value fn) {
        requireFunction(fn);
        messageHandlers.add(fn);
    }

    @HostAccess.Export
    public void each(Value fn) {
        requireFunction(fn);
        for (ScriptBot b : List.copyOf(bots.values())) fn.execute(b);
    }

    @HostAccess.Export
    public int count() {
        return bots.size();
    }

    @HostAccess.Export
    public void log(String text) {
        synchronized (log) {
            if (log.length() < 16_384) log.append(text).append('\n');
        }
    }

    List<Value> tickHandlers() {
        return tickHandlers;
    }

    List<Value> messageHandlers() {
        return messageHandlers;
    }

    ScriptBot attach(String name) {
        return bots.computeIfAbsent(name, ScriptBot::new);
    }

    void detach(String name) {
        bots.remove(name);
    }

    /** The log since the last call. */
    String takeLog() {
        synchronized (log) {
            String s = log.toString();
            log.setLength(0);
            return s;
        }
    }

    private static void requireFunction(Value fn) {
        if (fn == null || !fn.canExecute()) throw new IllegalArgumentException("expected a function");
    }
}
