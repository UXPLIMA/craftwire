package com.uxplima.craftwire.fixtures;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * `/cwfixture lag <ticks>`: burns a few milliseconds of every tick in a known method, for profile and trace; from a
 * scheduler task, or with `event` from the fixture's listener of the server's tick event.
 */
final class FixtureLag {
    static volatile long sink;
    /** Ticks the tick-event listener still burns. */
    static volatile int eventTicks;

    private FixtureLag() {}

    static void start(Plugin plugin, int ticks, boolean viaEvent) {
        if (viaEvent) {
            eventTicks = ticks;
            return;
        }
        int[] left = {ticks};
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (--left[0] < 0) {
                task.cancel();
                return;
            }
            burn(8);
        }, 1, 1);
    }

    /** Spins for `ms` milliseconds. */
    static void burn(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        long s = 0;
        while (System.nanoTime() < end) s += (s * 31) ^ 7;
        sink = s;
    }
}
