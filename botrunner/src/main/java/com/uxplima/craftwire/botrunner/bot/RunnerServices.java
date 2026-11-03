package com.uxplima.craftwire.botrunner.bot;

import com.uxplima.craftwire.bot.behavior.BehaviorFactory;
import com.uxplima.craftwire.bot.path.PathBudget;
import com.uxplima.craftwire.bot.path.PathCache;
import com.uxplima.craftwire.bot.resourcepack.PackVerifier;
import com.uxplima.craftwire.botrunner.loop.RunnerLoop;
import com.uxplima.craftwire.botrunner.world.WorldCache;
import java.nio.file.Path;

/**
 * What every bot of the runner shares: the loop, one pathfinding budget and path cache for all bots (so a thousand
 * bots cannot each spend a full search per tick), behaviors, resource pack checks, the shared world cache and the
 * runner's home folder (version jars, accounts).
 */
public record RunnerServices(RunnerLoop loop, PathBudget pathBudget, PathCache pathCache, BehaviorFactory behaviors, PackVerifier packs,
                             WorldCache worlds, Path home) {
    public static RunnerServices create(RunnerLoop loop, Path home) {
        return new RunnerServices(loop, PathBudget.standard(), PathCache.standard(), new BehaviorFactory(),
                new PackVerifier(home.resolve("cache").resolve("resourcepacks")), new WorldCache(), home);
    }
}
