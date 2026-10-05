package com.uxplima.craftwire.core;

import com.google.gson.JsonObject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.BiConsumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Copies INFO-and-above log4j events into a bounded backlog and, while attached, to the hub as {@code log} events.
 * Minecraft and Paper both ship log4j-core, so this adds no runtime dependency.
 */
public final class LogCapture extends AbstractAppender {
    public record Entry(long time, JsonObject data) {}

    static final int MAX_TEXT = 8_192;
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

    private final int capacity;
    private final ArrayDeque<Entry> backlog = new ArrayDeque<>();
    private BiConsumer<JsonObject, Long> sink;

    private LogCapture(int capacity) {
        super("craftwire-capture", null, null, true, Property.EMPTY_ARRAY);
        this.capacity = capacity;
    }

    /** Attaches a capture to the root logger. Call as early as possible so startup lines are kept for replay. */
    public static LogCapture install(int capacity) {
        LogCapture capture = new LogCapture(capacity);
        capture.start();
        ((Logger) LogManager.getRootLogger()).addAppender(capture);
        return capture;
    }

    public void uninstall() {
        ((Logger) LogManager.getRootLogger()).removeAppender(this);
        stop();
    }

    @Override
    public void append(LogEvent event) {
        if (!event.getLevel().isMoreSpecificThan(Level.INFO) || INSIDE.get()) return;
        INSIDE.set(true);   // a sink that logs (e.g. a network error) must not recurse into itself
        try {
            record(event.getInstant().getEpochMillisecond(), toJson(event));
        } finally {
            INSIDE.set(false);
        }
    }

    static JsonObject toJson(LogEvent e) {
        JsonObject d = new JsonObject();
        d.addProperty("level", e.getLevel().name());
        d.addProperty("logger", e.getLoggerName() == null ? "" : e.getLoggerName());
        d.addProperty("thread", e.getThreadName());
        d.addProperty("message", cut(e.getMessage() == null ? "" : e.getMessage().getFormattedMessage()));
        if (e.getThrown() != null) {
            StringWriter w = new StringWriter();
            e.getThrown().printStackTrace(new PrintWriter(w));
            d.addProperty("thrown", cut(w.toString()));
        }
        return d;
    }

    private static String cut(String s) {
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "…";
    }

    private synchronized void record(long time, JsonObject data) {
        backlog.addLast(new Entry(time, data));
        if (backlog.size() > capacity) backlog.removeFirst();
        if (sink == null) return;
        try {
            sink.accept(data, time);
        } catch (RuntimeException ignored) {
            // the hub link is best effort; logging must never fail because of it
        }
    }

    /** Replays the backlog to {@code sink}, then forwards every new entry until {@link #detach()}. */
    public synchronized void attach(BiConsumer<JsonObject, Long> sink) {
        for (Entry e : backlog) {
            try {
                sink.accept(e.data(), e.time());
            } catch (RuntimeException ignored) {
                // same as record(): never let the hub link break the caller
            }
        }
        this.sink = sink;
    }

    public synchronized void detach() {
        sink = null;
    }

    public synchronized List<Entry> backlog() {
        return List.copyOf(backlog);
    }
}
