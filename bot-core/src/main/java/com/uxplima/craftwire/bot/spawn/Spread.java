package com.uxplima.craftwire.bot.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Random columns within a radius for spread-out spawns, uniform over the disc. The bot kind finds a safe surface
 * block in each column; the seed makes a load test place its bots the same way every run.
 */
public final class Spread {
    public record Column(int x, int z) {}

    private Spread() {}

    public static List<Column> columns(double centerX, double centerZ, int radius, int count, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        List<Column> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = r.nextDouble(Math.PI * 2);
            // sqrt keeps the density even: without it the points crowd the centre.
            double dist = Math.sqrt(r.nextDouble()) * radius;
            out.add(new Column((int) Math.floor(centerX + Math.cos(angle) * dist), (int) Math.floor(centerZ + Math.sin(angle) * dist)));
        }
        return out;
    }
}
