package com.uxplima.craftwire.botrunner.loop;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The bot runner loop: one thread that owns every bot's state and ticks at 20 Hz, like a server tick. Network threads
 * never touch a bot; they hand decoded packets over with {@link #execute}, which runs them on this thread before the
 * next tick. Requests from the hub reach bots the same way, through {@link #call}.
 */
public final class RunnerLoop implements AutoCloseable {
    public static final long TICK_MS = 50;
    private static final Logger LOG = LogManager.getLogger("craftwire-botrunner");

    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "craftwire-botrunner-loop");
        t.setDaemon(false);
        return t;
    });
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final List<LongConsumer> tickers = new CopyOnWriteArrayList<>();
    private volatile Thread owner;
    private volatile long lastTickNanos;

    public void start() {
        thread.scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Called every tick, after the queued tasks, with the wall-clock time in milliseconds. */
    public void onTick(LongConsumer ticker) {
        tickers.add(ticker);
    }

    /** Runs the work on the loop before the next tick; from the loop itself it runs at once. */
    public void execute(Runnable work) {
        if (Thread.currentThread() == owner) {
            work.run();
        } else {
            tasks.add(work);
        }
    }

    public <T> CompletableFuture<T> call(Supplier<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        execute(() -> {
            try {
                f.complete(work.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    public boolean onLoop() {
        return Thread.currentThread() == owner;
    }

    /** How long the last tick took, in milliseconds; above 50 the runner cannot keep up with its bots. */
    public double lastTickMs() {
        return lastTickNanos / 1_000_000.0;
    }

    private void tick() {
        owner = Thread.currentThread();
        long start = System.nanoTime();
        try {
            Runnable r;
            while ((r = tasks.poll()) != null) run(r);
            long now = System.currentTimeMillis();
            for (LongConsumer t : tickers) run(() -> t.accept(now));
        } finally {
            lastTickNanos = System.nanoTime() - start;
        }
    }

    /** One failing task or ticker must never stop the loop (a scheduled task that throws is never run again). */
    private static void run(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            LOG.warn("Bot runner task failed", t);
        }
    }

    @Override
    public void close() {
        thread.shutdown();
        try {
            if (!thread.awaitTermination(5, TimeUnit.SECONDS)) thread.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            thread.shutdownNow();
        }
    }
}
