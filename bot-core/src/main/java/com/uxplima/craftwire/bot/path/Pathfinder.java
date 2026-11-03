package com.uxplima.craftwire.bot.path;

import java.util.List;

/**
 * A* over the positions a player can stand at. A node is a feet block position; its "standY" is where the feet
 * actually are (higher than the block when standing in a slab or carpet). Moves: walking to the 8 neighbours
 * (diagonals only past free corners), stepping or jumping up one block, dropping down to {@code maxFall}, swimming,
 * climbing ladders, and walking through doors and fence gates a hand can open. {@link #start} returns a search
 * that can run in slices across ticks under a {@link PathBudget}; {@link #find} runs one to the end.
 */
public final class Pathfinder {
    public enum Move { START, WALK, JUMP, DROP, SWIM, CLIMB, DOOR }

    public record Options(int maxFall, boolean openDoors, int maxNodes) {}

    public record Step(int x, int y, int z, double standY, Move move) {}

    /**
     * steps: start to the goal, or to `closest` (the reached position nearest the goal) when incomplete.
     * unloaded: the search ran into chunks that are not loaded, so a way may exist beyond them.
     */
    public record Result(List<Step> steps, boolean complete, Step closest, int explored, boolean unloaded) {}

    private Pathfinder() {}

    public static PathSearch start(BlockView view, int sx, int sy, int sz, int gx, int gy, int gz, Options o) {
        return new PathSearch(view, sx, sy, sz, gx, gy, gz, o);
    }

    public static Result find(BlockView view, int sx, int sy, int sz, int gx, int gy, int gz, Options o) {
        PathSearch s = start(view, sx, sy, sz, gx, gy, gz, o);
        s.run(Integer.MAX_VALUE, Long.MAX_VALUE);
        return s.result();
    }
}
