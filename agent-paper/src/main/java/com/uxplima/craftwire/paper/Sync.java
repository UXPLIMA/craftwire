package com.uxplima.craftwire.paper;

import com.uxplima.craftwire.core.AgentError;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Runs work on the thread that owns it and returns a future: the global region (console, plugins, server state), the
 * region of a chunk (blocks), or an entity's own scheduler (players and bots; it follows them across regions). On
 * Paper all three are the main thread; on Folia they are different threads, and touching data from the wrong one
 * throws. Never blocks the caller; exceptions complete the future instead of reaching the server loop.
 */
public final class Sync {
    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private final Plugin plugin;

    public Sync(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Whether this server is Folia (regionised: no main thread). */
    public static boolean folia() {
        return FOLIA;
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

    public <T> CompletableFuture<T> region(Location at, Callable<T> work) {
        return region(at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4, work);
    }

    /** Loads the chunk (asynchronously), then runs the work on the thread that owns it. */
    public <T> CompletableFuture<T> loaded(World world, int chunkX, int chunkZ, Callable<T> work) {
        return world.getChunkAtAsync(chunkX, chunkZ).thenCompose(chunk -> region(world, chunkX, chunkZ, work));
    }

    /**
     * On Folia, a failure from touching a block, entity or player off its region thread becomes WRONG_THREAD with
     * a hint where to run instead; anything else is returned unchanged.
     */
    public static Throwable explainThread(Throwable t) {
        Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
        if (!FOLIA || !String.valueOf(cause.getMessage()).contains("Thread failed main thread check")) return cause;
        return new AgentError("WRONG_THREAD", cause.getMessage(),
                "Folia runs every block, entity and player on the thread of its region. Pass at {world,x,z} to run "
                        + "where that block or entity is, or asPlayer to run on a player's thread; without either, code "
                        + "runs on the global region and may only touch server-wide state.");
    }

    /** On the entity's own scheduler; fails with ENTITY_GONE when the entity was removed before the work ran. */
    public <T> CompletableFuture<T> entity(Entity entity, Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Runnable retired = () -> f.completeExceptionally(gone(entity));
        if (!entity.getScheduler().execute(plugin, () -> complete(f, work), retired, 1L)) retired.run();
        return f;
    }

    /** Repeats on the global region every {@code period} ticks; the task cancels itself through the argument. */
    public ScheduledTask repeatGlobal(Consumer<ScheduledTask> task, long delay, long period) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task, Math.max(1, delay), period);
    }

    /** Repeats on the entity's scheduler every tick; {@code retired} runs if the entity goes away. Null if it already has. */
    public ScheduledTask repeatEntity(Entity entity, Consumer<ScheduledTask> task, Runnable retired) {
        return entity.getScheduler().runAtFixedRate(plugin, task, retired, 1L, 1L);
    }

    private static AgentError gone(Entity entity) {
        return new AgentError("ENTITY_GONE", entity.getName() + " left the world before the request ran.",
                "Check world_query {action:'players'} and retry.");
    }

    private static <T> void complete(CompletableFuture<T> f, Callable<T> work) {
        try {
            f.complete(work.call());
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, Sync.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
