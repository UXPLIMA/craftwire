package com.uxplima.craftwire.paper.world.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * world.render: a PNG of a region without any client. Chunks are read as snapshots on the server thread (loaded or
 * from disk; ungenerated ones are left out unless `generate`), and the image is drawn on another thread.
 */
public final class WorldRenderHandler {
    private WorldRenderHandler() {}

    static final int MAX_BLOCKS = 512;
    static final int MAX_PIXELS = 2048;
    static final long MAX_SIDE_CHECKS = 64L * 1024 * 1024;

    private record Request(World world, Region region, String viewName, JsonObject p, int grid, boolean players, boolean generate) {}

    public static CompletableFuture<JsonElement> handle(JsonObject p, CraftwirePlugin plugin) {
        return plugin.sync().global(() -> request(p)).thenCompose(req -> {
            // Chunk loads complete on the server thread; snapshots are taken there too.
            Map<Long, ChunkSnapshot> chunks = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> loads = new ArrayList<>();
            Region rg = req.region();
            CompletableFuture<List<Marker>> markers = plugin.sync().global(() -> {
                Region rgn = req.region();
                int cx1 = rgn.x1() >> 4, cx2 = rgn.x2() >> 4, cz1 = (rgn.z1() - 1) >> 4, cz2 = rgn.z2() >> 4;
                for (int cx = cx1; cx <= cx2; cx++) {
                    for (int cz = cz1; cz <= cz2; cz++) {
                        int x = cx, z = cz;
                        loads.add(req.world().getChunkAtAsync(x, z, req.generate()).thenAccept(chunk -> {
                            if (chunk != null) chunks.put(SnapshotSource.key(x, z), chunk.getChunkSnapshot(true, false, false));
                        }));
                    }
                }
                List<Marker> out = new ArrayList<>();
                if (req.players()) {
                    for (Player pl : req.world().getPlayers()) out.add(new Marker(pl.getName(), pl.getX(), pl.getY(), pl.getZ()));
                }
                return out;
            });
            return markers.thenCompose(m -> CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenApply(v -> m))
                    .thenApplyAsync(m -> draw(req, new SnapshotSource(chunks, req.world().getMinHeight(), req.world().getMaxHeight()), m, rg));
        });
    }

    private static Request request(JsonObject p) {
        World world = Args.world(p);
        Region rg = new Region(Args.integer(p, "x1"), Args.integer(p, "z1"), Args.integer(p, "x2"), Args.integer(p, "z2"));
        if (rg.width() > MAX_BLOCKS || rg.depth() > MAX_BLOCKS) {
            throw new AgentError("INVALID_PARAMS", "The region is " + rg.width() + "x" + rg.depth() + " blocks; the limit is " + MAX_BLOCKS + "x" + MAX_BLOCKS,
                    "Render a smaller area, or several renders side by side.");
        }
        String view = Args.optString(p, "view").orElse("top").toLowerCase(Locale.ROOT);
        if (!List.of("top", "slice", "side").contains(view)) throw Args.invalid("view must be top, slice or side");
        if (view.equals("slice") && !p.has("y")) throw Args.invalid("slice needs y");
        return new Request(world, rg, view, p, Math.max(0, Args.optInt(p, "grid").orElse(0)), Args.bool(p, "players", true), Args.bool(p, "generate", false));
    }

    private static JsonElement draw(Request req, SnapshotSource src, List<Marker> markers, Region rg) {
        JsonObject p = req.p();
        View view = switch (req.viewName()) {
            case "slice" -> new View.Slice(Args.integer(p, "y"));
            case "side" -> side(p, src, rg);
            default -> new View.Top();
        };
        int columns, rows;
        if (view instanceof View.Side s) {
            boolean alongX = s.facing() == View.Facing.NORTH || s.facing() == View.Facing.SOUTH;
            columns = alongX ? rg.width() : rg.depth();
            rows = Math.abs(s.y2() - s.y1()) + 1;
            long checks = (long) columns * rows * (alongX ? rg.depth() : rg.width());
            if (checks > MAX_SIDE_CHECKS) {
                throw new AgentError("INVALID_PARAMS", "This side view would look through " + checks + " blocks",
                        "Make the region shallower in the facing direction, or narrow y1..y2.");
            }
        } else {
            columns = rg.width();
            rows = rg.depth();
        }
        int longest = Math.max(columns, rows);
        int maxScale = Math.max(1, Math.min(8, MAX_PIXELS / longest));
        int scale = Math.min(maxScale, Args.optInt(p, "scale").orElse(Math.max(1, Math.min(8, 1024 / longest))));
        scale = Math.max(1, scale);

        Render r = new Renderer(src).render(view, rg, scale, req.grid(), markers);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(r.image(), "png", png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        JsonObject o = new JsonObject();
        o.addProperty("mime", "image/png");
        o.addProperty("data", Base64.getEncoder().encodeToString(png.toByteArray()));
        o.addProperty("width", r.image().getWidth());
        o.addProperty("height", r.image().getHeight());
        o.addProperty("view", req.viewName());
        JsonObject region = new JsonObject();
        region.addProperty("x1", rg.x1());
        region.addProperty("z1", rg.z1());
        region.addProperty("x2", rg.x2());
        region.addProperty("z2", rg.z2());
        o.add("region", region);
        o.addProperty("scale", scale);
        JsonObject origin = new JsonObject();
        origin.addProperty("x", r.originX());
        origin.addProperty("y", r.originY());
        o.add("origin", origin);
        o.addProperty("orientation", orientation(view, rg));
        if (view instanceof View.Side s) {
            o.addProperty("y1", Math.min(s.y1(), s.y2()));
            o.addProperty("y2", Math.max(s.y1(), s.y2()));
        }
        if (r.unloadedColumns() > 0) o.addProperty("unloadedColumns", r.unloadedColumns());
        JsonArray names = new JsonArray();
        for (Marker m : markers) names.add(m.label());
        if (!names.isEmpty()) o.add("players", names);
        return o;
    }

    private static View side(JsonObject p, SnapshotSource src, Region rg) {
        View.Facing facing;
        try {
            facing = View.Facing.valueOf(Args.optString(p, "facing").orElse("north").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw Args.invalid("facing must be north, south, east or west");
        }
        if (p.has("y1") && p.has("y2")) return new View.Side(facing, Args.integer(p, "y1"), Args.integer(p, "y2"));
        // Default height range: from a little below the lowest surface to just above the highest one.
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = rg.x1(); x <= rg.x2(); x++) {
            for (int z = rg.z1(); z <= rg.z2(); z++) {
                if (!src.loaded(x, z)) continue;
                int y = src.top(x, z);
                while (y > src.minY() && src.color(x, y, z) == 0) y--;
                low = Math.min(low, y);
                high = Math.max(high, y);
            }
        }
        if (low == Integer.MAX_VALUE) return new View.Side(facing, 60, 80);
        int y1 = p.has("y1") ? Args.integer(p, "y1") : Math.max(src.minY(), low - 8);
        int y2 = p.has("y2") ? Args.integer(p, "y2") : Math.min(src.maxY() - 1, high + 3);
        return new View.Side(facing, y1, y2);
    }

    private static String orientation(View view, Region rg) {
        return switch (view) {
            case View.Side s -> "Looking " + s.facing().name().toLowerCase(Locale.ROOT) + "; up is +y; to the right is " + switch (s.facing()) {
                case NORTH -> "+x (east)";
                case SOUTH -> "-x (west)";
                case EAST -> "+z (south)";
                case WEST -> "-z (north)";
            } + ". Nearer blocks are brighter.";
            default -> "North is up: x grows to the right, z grows downward. The world starts at pixel origin; one block is `scale` pixels.";
        };
    }
}
