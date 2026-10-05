package com.uxplima.craftwire.fabric;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** Bridges hub threads to the client thread. All state here is touched on the client thread only. */
public final class ClientScheduler {
    private record Pending(long due, Runnable task) {}

    private final List<Pending> pending = new ArrayList<>();
    private long tick;

    public <T> CompletableFuture<T> call(Supplier<T> onClientThread) {
        return Minecraft.getInstance().submit(onClientThread);
    }

    public CompletableFuture<Void> delay(int ticks) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> pending.add(new Pending(tick + Math.max(1, ticks), () -> f.complete(null))));
        return f;
    }

    public void onEndTick() {
        tick++;
        if (pending.isEmpty()) return;
        List<Runnable> due = new ArrayList<>();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.due() <= tick) {
                due.add(p.task());
                it.remove();
            }
        }
        for (Runnable r : due) {
            try {
                r.run();
            } catch (RuntimeException e) {
                CraftwireAgent.LOGGER.error("Craftwire scheduled task failed", e);
            }
        }
    }
}
