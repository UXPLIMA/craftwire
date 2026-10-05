package com.uxplima.craftwire.paper;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Runs work on the thread that owns it (the main thread on Paper, the owning region on Folia) and returns a future.
 * Never blocks the caller; exceptions complete the future instead of reaching the server loop.
 */
public final class Sync {
    private final Plugin plugin;

    public Sync(Plugin plugin) {
        this.plugin = plugin;
    }

    public <T> CompletableFuture<T> global(Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> complete(f, work));
        return f;
    }

    public <T> CompletableFuture<T> region(World world, int chunkX, int chunkZ, Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> complete(f, work));
        return f;
    }

    private static <T> void complete(CompletableFuture<T> f, Callable<T> work) {
        try {
            f.complete(work.call());
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
    }
}
