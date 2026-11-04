package com.uxplima.craftwire.bot.path;

import com.uxplima.craftwire.bot.path.Cell.Kind;
import com.uxplima.craftwire.bot.path.Pathfinder.Move;
import com.uxplima.craftwire.bot.path.Pathfinder.Step;
import java.util.ArrayList;
import java.util.List;

/** What a player can do from one standing position: the moves A* expands. Same thread as its search. */
final class MoveRules {
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /** The highest a jump gets the feet, minus a margin. */
    private static final double MAX_RISE = 1.2;
    private static final double STEP = 0.6;

    private final BlockView view;
    private final int maxFall;
    private final boolean openDoors;
    private boolean unloaded;

    MoveRules(BlockView view, int maxFall, boolean openDoors) {
        this.view = view;
        this.maxFall = maxFall;
        this.openDoors = openDoors;
    }

    /** Whether a lookup hit a chunk that is not loaded. */
    boolean unloaded() {
        return unloaded;
    }

    private boolean body(Cell c) {
        return switch (c.kind()) {
            case AIR, WATER, CLIMB -> true;
            case DOOR -> openDoors;
            default -> false;
        };
    }

    private Cell at(int x, int y, int z) {
        Cell c = view.cell(x, y, z);
        if (c.kind() == Kind.UNKNOWN) unloaded = true;
        return c;
    }

    /** Where the feet are when standing at this node (no check that one can stand there). */
    double standY(int x, int y, int z) {
        Cell f = at(x, y, z);
        return f.kind() == Kind.LOW ? y + f.height() : y;
    }

    /** Whether a player can stand (or swim, or hold on to a ladder) with the feet in this block. */
    private boolean standable(int x, int y, int z) {
        Cell f = at(x, y, z);
        if (f.kind() == Kind.LOW) {
            return body(at(x, y + 1, z)) && (f.height() <= 0.2 || body(at(x, y + 2, z)));
        }
        if (!body(f) || !body(at(x, y + 1, z))) return false;
        if (f.kind() == Kind.WATER || f.kind() == Kind.CLIMB) return true;
        return at(x, y - 1, z).kind() == Kind.SOLID;
    }

    /**
     * Whether the goal test (feet within one block of y at x, z) can never pass: no cell there can be stood in, judged
     * on loaded blocks only. An unloaded block there may hide a floor, so that is not judged unreachable.
     */
    boolean nowhereToStand(int x, int y, int z) {
        for (int dy = -2; dy <= 3; dy++) if (view.cell(x, y + dy, z).kind() == Kind.UNKNOWN) return false;
        for (int dy = -1; dy <= 1; dy++) if (standable(x, y + dy, z)) return false;
        return true;
    }

    private boolean passable(int x, int y, int z) {
        return body(at(x, y, z)) && body(at(x, y + 1, z));
    }

    private Step node(int x, int y, int z, Step from, boolean vertical) {
        double sy = standY(x, y, z);
        Cell feet = at(x, y, z);
        Move m;
        if (feet.kind() == Kind.WATER) m = Move.SWIM;
        else if (vertical && (feet.kind() == Kind.CLIMB || at(from.x(), from.y(), from.z()).kind() == Kind.CLIMB)) m = Move.CLIMB;
        else if (feet.kind() == Kind.DOOR || at(x, y + 1, z).kind() == Kind.DOOR) m = Move.DOOR;
        else if (y < from.y()) m = Move.DROP;
        else if (sy - from.standY() > STEP) m = Move.JUMP;
        else m = Move.WALK;
        return new Step(x, y, z, sy, m);
    }

    List<Step> neighbours(Step n) {
        List<Step> out = new ArrayList<>();
        int x = n.x();
        int y = n.y();
        int z = n.z();
        boolean headroom = body(at(x, y + 2, z));
        for (int[] d : DIRS) horizontal(n, d[0], d[1], headroom, out);
        // Straight up and down: swimming and climbing.
        Cell feet = at(x, y, z);
        if ((feet.kind() == Kind.WATER || feet.kind() == Kind.CLIMB) && standable(x, y + 1, z)) out.add(node(x, y + 1, z, n, true));
        Cell below = at(x, y - 1, z);
        if ((below.kind() == Kind.WATER || below.kind() == Kind.CLIMB) && standable(x, y - 1, z)) out.add(node(x, y - 1, z, n, true));
        if (at(x, y + 1, z).kind() == Kind.CLIMB && standable(x, y + 1, z)) out.add(node(x, y + 1, z, n, true));
        return out;
    }

    private void horizontal(Step n, int dx, int dz, boolean headroom, List<Step> out) {
        int x = n.x();
        int y = n.y();
        int z = n.z();
        int nx = x + dx;
        int nz = z + dz;
        boolean diagonal = dx != 0 && dz != 0;
        // Diagonals only when both sides are free at the levels the move passes through.
        if (diagonal && !(passable(x + dx, y, z) && passable(x, y, z + dz))) return;
        if (standable(nx, y, nz)) {
            if (standY(nx, y, nz) - n.standY() <= MAX_RISE) out.add(node(nx, y, nz, n, false));
            return;
        }
        // One block up: a step, or a jump that needs room above the head.
        if (!diagonal && standable(nx, y + 1, nz) && standY(nx, y + 1, nz) - n.standY() <= MAX_RISE
                && (headroom || standY(nx, y + 1, nz) - n.standY() <= STEP)) {
            out.add(node(nx, y + 1, nz, n, false));
            return;
        }
        // Down: walk off the edge and fall, through free blocks, at most maxFall.
        if (!passable(nx, y, nz)) return;
        for (int k = 1; k <= maxFall; k++) {
            if (standable(nx, y - k, nz)) {
                out.add(node(nx, y - k, nz, n, false));
                return;
            }
            if (!body(at(nx, y - k, nz))) return;
        }
    }
}
