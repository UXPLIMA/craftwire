package com.uxplima.craftwire.paper.bot.path;

import com.uxplima.craftwire.paper.bot.path.Cell.Kind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over the positions a player can stand at. A node is a feet block position; its "standY" is where the feet
 * actually are (higher than the block when standing in a slab or carpet). Moves: walking to the 8 neighbours
 * (diagonals only past free corners), stepping or jumping up one block, dropping down to {@code maxFall}, swimming,
 * climbing ladders, and walking through doors and fence gates a hand can open.
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

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /** The highest a jump gets the feet, minus a margin. */
    private static final double MAX_RISE = 1.2;
    private static final double STEP = 0.6;

    private final BlockView view;
    private final Options o;
    private boolean unloaded;

    private Pathfinder(BlockView view, Options o) {
        this.view = view;
        this.o = o;
    }

    public static Result find(BlockView view, int sx, int sy, int sz, int gx, int gy, int gz, Options o) {
        return new Pathfinder(view, o).search(sx, sy, sz, gx, gy, gz);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + (1 << 25)) << 38) | ((long) (y + 2048) << 26) | (z + (1 << 25));
    }

    private record Node(int x, int y, int z, double standY, Move move) {}

    private record Open(Node node, double f, long key) {}

    private Result search(int sx, int sy, int sz, int gx, int gy, int gz) {
        Map<Long, Double> g = new HashMap<>();
        Map<Long, Node> nodes = new HashMap<>();
        Map<Long, Long> from = new HashMap<>();
        PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));

        Node start = new Node(sx, sy, sz, standY(sx, sy, sz), Move.START);
        long startKey = key(sx, sy, sz);
        g.put(startKey, 0.0);
        nodes.put(startKey, start);
        open.add(new Open(start, h(start, gx, gy, gz), startKey));
        Node closest = start;
        long closestKey = startKey;
        double closestH = h(start, gx, gy, gz);
        int explored = 0;

        while (!open.isEmpty()) {
            Open cur = open.poll();
            Node n = cur.node();
            double gn = g.get(cur.key());
            if (cur.f() > gn + h(n, gx, gy, gz) + 1e-9) continue;   // a stale entry
            if (n.x() == gx && n.z() == gz && Math.abs(n.y() - gy) <= 1) {
                return new Result(path(cur.key(), nodes, from), true, step(n), explored, unloaded);
            }
            if (++explored > o.maxNodes()) break;
            double hn = h(n, gx, gy, gz);
            if (hn < closestH) {
                closest = n;
                closestKey = cur.key();
                closestH = hn;
            }
            for (Node next : neighbours(n)) {
                long k = key(next.x(), next.y(), next.z());
                double cost = gn + cost(n, next);
                Double known = g.get(k);
                if (known != null && known <= cost) continue;
                g.put(k, cost);
                nodes.put(k, next);
                from.put(k, cur.key());
                open.add(new Open(next, cost + h(next, gx, gy, gz), k));
            }
        }
        return new Result(path(closestKey, nodes, from), false, step(closest), Math.min(explored, o.maxNodes()), unloaded);
    }

    private static Step step(Node n) {
        return new Step(n.x(), n.y(), n.z(), n.standY(), n.move());
    }

    private static List<Step> path(long end, Map<Long, Node> nodes, Map<Long, Long> from) {
        List<Step> out = new ArrayList<>();
        Long k = end;
        while (k != null) {
            out.add(step(nodes.get(k)));
            k = from.get(k);
        }
        Collections.reverse(out);
        return out;
    }

    private static double h(Node n, int gx, int gy, int gz) {
        double dx = n.x() - gx;
        double dy = n.y() - gy;
        double dz = n.z() - gz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double cost(Node a, Node b) {
        double d = Math.hypot(b.x() - a.x(), b.z() - a.z());
        return switch (b.move()) {
            case JUMP -> d + 0.5;
            case DROP -> d + 0.4 * (a.y() - b.y());
            case SWIM -> Math.max(d, 1) * 2;
            case CLIMB -> 1.5;
            case DOOR -> d + 2;
            default -> d;
        };
    }

    // --- what a cell allows ---

    private boolean body(Cell c) {
        return switch (c.kind()) {
            case AIR, WATER, CLIMB -> true;
            case DOOR -> o.openDoors();
            default -> false;
        };
    }

    private Cell at(int x, int y, int z) {
        Cell c = view.cell(x, y, z);
        if (c.kind() == Kind.UNKNOWN) unloaded = true;
        return c;
    }

    /** Where the feet are when standing at this node (no check that one can stand there). */
    private double standY(int x, int y, int z) {
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

    private boolean passable(int x, int y, int z) {
        return body(at(x, y, z)) && body(at(x, y + 1, z));
    }

    private Node node(int x, int y, int z, Node from, boolean vertical) {
        double sy = standY(x, y, z);
        Cell feet = at(x, y, z);
        Move m;
        if (feet.kind() == Kind.WATER) m = Move.SWIM;
        else if (vertical && (feet.kind() == Kind.CLIMB || at(from.x(), from.y(), from.z()).kind() == Kind.CLIMB)) m = Move.CLIMB;
        else if (feet.kind() == Kind.DOOR || at(x, y + 1, z).kind() == Kind.DOOR) m = Move.DOOR;
        else if (y < from.y()) m = Move.DROP;
        else if (sy - from.standY() > STEP) m = Move.JUMP;
        else m = Move.WALK;
        return new Node(x, y, z, sy, m);
    }

    private List<Node> neighbours(Node n) {
        List<Node> out = new ArrayList<>();
        int x = n.x();
        int y = n.y();
        int z = n.z();
        boolean headroom = body(at(x, y + 2, z));
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int nz = z + d[1];
            boolean diagonal = d[0] != 0 && d[1] != 0;
            // Diagonals only when both sides are free at the levels the move passes through.
            if (diagonal && !(passable(x + d[0], y, z) && passable(x, y, z + d[1]))) continue;

            if (standable(nx, y, nz)) {
                if (standY(nx, y, nz) - n.standY() <= MAX_RISE) out.add(node(nx, y, nz, n, false));
                continue;
            }
            // One block up: a step, or a jump that needs room above the head.
            if (!diagonal && standable(nx, y + 1, nz) && standY(nx, y + 1, nz) - n.standY() <= MAX_RISE
                    && (headroom || standY(nx, y + 1, nz) - n.standY() <= STEP)) {
                out.add(node(nx, y + 1, nz, n, false));
                continue;
            }
            // Down: walk off the edge and fall, through free blocks, at most maxFall.
            if (!passable(nx, y, nz)) continue;
            for (int k = 1; k <= o.maxFall(); k++) {
                if (standable(nx, y - k, nz)) {
                    out.add(node(nx, y - k, nz, n, false));
                    break;
                }
                if (!body(at(nx, y - k, nz))) break;
            }
        }
        // Straight up and down: swimming and climbing.
        Cell feet = at(x, y, z);
        if ((feet.kind() == Kind.WATER || feet.kind() == Kind.CLIMB) && standable(x, y + 1, z)) out.add(node(x, y + 1, z, n, true));
        Cell below = at(x, y - 1, z);
        if ((below.kind() == Kind.WATER || below.kind() == Kind.CLIMB) && standable(x, y - 1, z)) out.add(node(x, y - 1, z, n, true));
        if (at(x, y + 1, z).kind() == Kind.CLIMB && standable(x, y + 1, z)) out.add(node(x, y + 1, z, n, true));
        return out;
    }
}
