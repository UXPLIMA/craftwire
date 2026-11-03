package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;

/**
 * The behavior one bot runs now, if any. A behavior that throws on many ticks in a row is stopped and its error
 * kept for status, so one broken behavior cannot spam the log every tick. Bot thread only, except
 * {@link #describe}, which reads a snapshot.
 */
public final class BehaviorSlot {
    private static final int MAX_FAILURES = 20;

    private volatile Behavior current;
    private volatile long startedAt;
    private volatile String lastError;
    private int failures;

    /** Replaces the behavior; the old one is stopped on this (the bot's) thread. */
    public void set(BotControl bot, Behavior next, long now) {
        Behavior old = current;
        if (old != null) old.stop(bot);
        current = next;
        startedAt = now;
        failures = 0;
        lastError = null;
    }

    public void clear(BotControl bot) {
        Behavior old = current;
        current = null;
        if (old != null) old.stop(bot);
    }

    public boolean active() {
        return current != null;
    }

    public void tick(BotControl bot, long now) {
        Behavior b = current;
        if (b == null) return;
        try {
            b.tick(bot, now);
            failures = 0;
        } catch (RuntimeException e) {
            lastError = e instanceof AgentError a ? a.code() + ": " + a.getMessage() : e.toString();
            if (++failures >= MAX_FAILURES) {
                current = null;
                b.stop(bot);
            }
        }
    }

    public JsonObject describe(long now) {
        JsonObject o = new JsonObject();
        Behavior b = current;
        if (b == null) {
            o.addProperty("type", "none");
        } else {
            o = b.describe();
            o.addProperty("runningMs", now - startedAt);
        }
        if (lastError != null) o.addProperty("lastError", lastError);
        return o;
    }
}
