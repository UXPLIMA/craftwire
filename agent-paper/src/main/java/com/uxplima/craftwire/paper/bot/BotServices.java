package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.bot.behavior.BehaviorFactory;
import com.uxplima.craftwire.bot.path.PathBudget;
import com.uxplima.craftwire.bot.path.PathCache;
import com.uxplima.craftwire.bot.resourcepack.PackVerifier;
import com.uxplima.craftwire.paper.Sync;
import org.bukkit.plugin.Plugin;

/** What every bot of this server shares: the plugin, the scheduler, the path budget and cache, behaviors and the pack verifier. */
record BotServices(Plugin plugin, Sync sync, PathBudget pathBudget, PathCache pathCache, BehaviorFactory behaviors, PackVerifier packs) {}
