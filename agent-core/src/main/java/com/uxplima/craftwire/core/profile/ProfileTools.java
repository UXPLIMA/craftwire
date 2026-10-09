package com.uxplima.craftwire.core.profile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** The profile.run and trace.run requests, the same on servers and clients; the platform supplies the details. */
public final class ProfileTools implements AutoCloseable {
    /** What differs between a server and a client. */
    public interface Platform {
        /** The thread whose time `profile` samples ("Server thread", "Render thread"). */
        String gameThread();

        /** Which threads `profile` samples; by default the one named {@link #gameThread()}. */
        default Predicate<String> gameThreads() {
            return gameThread()::equals;
        }

        /** Who owns which classes (built off the game thread; may read every plugin or mod jar). */
        OwnerIndex owners();

        /** The event a listener method handles, for a frame without a descriptor; null when it is no listener. */
        default String eventOf(Frame frame) {
            return frame.eventParameter();
        }

        /** Starts feeding the session what only the game knows (tick times, frame rate). */
        Feed feed(Profiler.Session session);
    }

    /** Stops a feed; may add fields to the finished report. */
    public interface Feed {
        Feed NONE = report -> {};

        void stop(JsonObject report);
    }

    private final Platform platform;
    private final Set<AutoCloseable> running = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final ExecutorService threads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "craftwire-profile");
        t.setDaemon(true);
        return t;
    });

    public ProfileTools(Platform platform) {
        this.platform = platform;
    }

    public CompletableFuture<JsonElement> profile(JsonObject p) {
        long durationMs = clamp(p, "durationMs", 10_000, 1000, 60_000);
        int intervalMs = (int) clamp(p, "intervalMs", 10, 5, 50);
        int top = (int) clamp(p, "top", 15, 1, 50);
        return CompletableFuture.supplyAsync(() -> {
            OwnerIndex owners = platform.owners();
            Profiler.Session session = Profiler.start(platform.gameThread(), platform.gameThreads(), intervalMs, owners, platform::eventOf);
            AutoCloseable handle = session::cancel;
            running.add(handle);
            Feed feed = Feed.NONE;
            try {
                feed = platform.feed(session);
                sleep(durationMs);
            } catch (RuntimeException e) {
                session.cancel();
                feed.stop(new JsonObject());
                throw e;
            } finally {
                running.remove(handle);
            }
            JsonObject report = session.finish(top);
            feed.stop(report);
            return (JsonElement) report;
        }, threads);
    }

    public CompletableFuture<JsonElement> trace(JsonObject p) {
        String filter = string(p, "method");
        long durationMs = clamp(p, "durationMs", 10_000, 1000, 60_000);
        long minMs = clamp(p, "minMs", 0, 0, 60_000);
        int stackDepth = (int) clamp(p, "stackDepth", 8, 1, 32);
        int limit = (int) clamp(p, "limit", 50, 1, 200);
        MethodTracer.validate(filter);
        return CompletableFuture.supplyAsync(() -> {
            MethodTracer.Session session = MethodTracer.start(filter, minMs, stackDepth, limit, platform.owners());
            AutoCloseable handle = session::cancel;
            running.add(handle);
            try {
                CompletableFuture.anyOf(session.flooded(), closed).get(durationMs, TimeUnit.MILLISECONDS);
                if (closed.isDone()) throw new InterruptedException();
            } catch (java.util.concurrent.TimeoutException expected) {
                // the normal end: the duration passed
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.cancel();
                throw cancelled();
            } catch (java.util.concurrent.ExecutionException impossible) {
                // flooded() never completes exceptionally
            } finally {
                running.remove(handle);
            }
            JsonObject report = session.finish();
            if (report.getAsJsonArray("methods").isEmpty()) {
                report.addProperty("hint", "No method matched the filter. Use the class's binary name (com.example.Outer$Inner) "
                        + "and the method's exact name; classes are matched once they are loaded.");
            } else if (report.get("calls").getAsLong() == 0 && minMs == 0) {
                report.addProperty("hint", "The method exists but did not run during the trace: make it run (a command, a bot "
                        + "action) while tracing, or trace for longer.");
            }
            return (JsonElement) report;
        }, threads);
    }

    @Override
    public void close() {
        closed.complete(null);
        for (AutoCloseable c : running) {
            try {
                c.close();
            } catch (Exception ignored) {
                // shutting down
            }
        }
        threads.shutdown();   // queued requests still run, see `closed` and end with CANCELLED
    }

    /** Waits `ms`, or throws CANCELLED as soon as the tools are closed. */
    private void sleep(long ms) {
        try {
            closed.get(ms, TimeUnit.MILLISECONDS);
            throw cancelled();
        } catch (java.util.concurrent.TimeoutException expected) {
            // the normal end: the duration passed
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cancelled();
        } catch (java.util.concurrent.ExecutionException impossible) {
            throw cancelled();
        }
    }

    private static AgentError cancelled() {
        return new AgentError("CANCELLED", "The game is shutting down; the recording was dropped.", "Run it again once the game is back.");
    }

    private static String string(JsonObject p, String k) {
        JsonElement e = p.get(k);
        if (e == null || e.isJsonNull() || e.getAsString().isBlank()) {
            throw new AgentError("INVALID_PARAMS", "Missing " + k, "Pass " + k + ".");
        }
        return e.getAsString().strip();
    }

    private static long clamp(JsonObject p, String k, long fallback, long min, long max) {
        JsonElement e = p.get(k);
        long v = e == null || e.isJsonNull() ? fallback : e.getAsLong();
        return Math.clamp(v, min, max);
    }
}
