package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import com.uxplima.craftwire.paper.world.BlockMatcher;
import com.uxplima.craftwire.paper.world.Box;
import com.uxplima.craftwire.paper.world.ChunkWork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

final class WorldQueryHandler {
    static final long MAX_REGION = 32_768;
    static final long MAX_SCAN = 4_000_000;
    static final int MAX_ENTITY_CHUNKS = 1024;

    private WorldQueryHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, Sync sync, Predicate<String> isBot) {
        String action = Args.string(p, "action");
        return switch (action) {
            case "players" -> sync.global(() -> players(isBot));
            case "block" -> block(p, sync);
            case "region" -> region(p, sync);
            case "entities" -> entities(p, sync);
            case "find_block" -> findBlock(p, sync);
            default -> throw Args.invalid("Unknown action: " + action);
        };
    }

    private static CompletableFuture<JsonElement> block(JsonObject p, Sync sync) {
        World w = Args.world(p);
        int x = Args.integer(p, "x"), y = Args.integer(p, "y"), z = Args.integer(p, "z");
        return ChunkWork.forEachChunk(sync, w, Box.of(x, y, z, x, y, z), part -> {
            JsonObject o = new JsonObject();
            o.addProperty("world", w.getName());
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("block", w.getBlockAt(x, y, z).getBlockData().getAsString());
            return (JsonElement) o;
        }).thenApply(list -> list.get(0));
    }

    private record Cell(int index, String state) {}

    private static CompletableFuture<JsonElement> region(JsonObject p, Sync sync) {
        World w = Args.world(p);
        Box box = Box.from(p);
        if (box.volume() > MAX_REGION) {
            throw new AgentError("QUERY_TOO_LARGE", "The region covers " + box.volume() + " blocks; the limit is " + MAX_REGION,
                    "Query a smaller box, or use find_block to locate specific blocks in a large area.");
        }
        String encoding = Args.optString(p, "encoding").orElse("runs");
        if (!encoding.equals("runs") && !encoding.equals("indices")) throw Args.invalid("encoding must be runs or indices");
        return ChunkWork.forEachChunk(sync, w, box, part -> readCells(w, box, part)).thenApply(parts -> assemble(box, parts, encoding));
    }

    private static List<Cell> readCells(World w, Box whole, Box part) {
        ChunkSnapshot snap = w.getChunkAt(part.minX() >> 4, part.minZ() >> 4).getChunkSnapshot(false, false, false);
        List<Cell> cells = new ArrayList<>((int) part.volume());
        for (int y = part.minY(); y <= part.maxY(); y++) {
            for (int z = part.minZ(); z <= part.maxZ(); z++) {
                for (int x = part.minX(); x <= part.maxX(); x++) {
                    int index = ((y - whole.minY()) * whole.sizeZ() + (z - whole.minZ())) * whole.sizeX() + (x - whole.minX());
                    cells.add(new Cell(index, state(snap, w, x, y, z)));
                }
            }
        }
        return cells;
    }

    private static String state(ChunkSnapshot snap, World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return "minecraft:void_air";
        return snap.getBlockData(x & 15, y, z & 15).getAsString();
    }

    private static JsonElement assemble(Box box, List<List<Cell>> parts, String encoding) {
        int[] blocks = new int[(int) box.volume()];
        Map<String, Integer> palette = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Cell> all = parts.stream().flatMap(List::stream).sorted(Comparator.comparingInt(Cell::index)).toList();
        for (Cell c : all) {
            blocks[c.index()] = palette.computeIfAbsent(c.state(), s -> palette.size());
            counts.merge(c.state(), 1, Integer::sum);
        }
        JsonObject o = box.toJson();
        JsonObject size = new JsonObject();
        size.addProperty("x", box.sizeX());
        size.addProperty("y", box.sizeY());
        size.addProperty("z", box.sizeZ());
        o.add("size", size);
        JsonArray pal = new JsonArray();
        palette.keySet().forEach(pal::add);
        o.add("palette", pal);
        if (encoding.equals("indices")) {
            JsonArray arr = new JsonArray(blocks.length);
            for (int b : blocks) arr.add(b);
            o.add("blocks", arr);
        } else {
            // [palette index, count] runs in the same order: a mostly empty region shrinks from thousands of numbers to a few.
            JsonArray runs = new JsonArray();
            for (int i = 0; i < blocks.length; ) {
                int j = i;
                while (j < blocks.length && blocks[j] == blocks[i]) j++;
                JsonArray run = new JsonArray(2);
                run.add(blocks[i]);
                run.add(j - i);
                runs.add(run);
                i = j;
            }
            o.add("runs", runs);
        }
        JsonObject cnt = new JsonObject();
        counts.forEach(cnt::addProperty);
        o.add("counts", cnt);
        return o;
    }

    private static CompletableFuture<JsonElement> entities(JsonObject p, Sync sync) {
        World w = Args.world(p);
        Box box = Box.from(p);
        if (box.chunks().size() > MAX_ENTITY_CHUNKS) {
            throw new AgentError("QUERY_TOO_LARGE", "The box touches " + box.chunks().size() + " chunks; the limit is " + MAX_ENTITY_CHUNKS,
                    "Search a smaller box.");
        }
        Optional<String> type = Args.optString(p, "type")
                .map(t -> t.toLowerCase(Locale.ROOT))
                .map(t -> t.contains(":") ? t : "minecraft:" + t);
        int limit = Args.optInt(p, "limit").orElse(100);
        return ChunkWork.forEachChunk(sync, w, box, part -> {
            BoundingBox bb = new BoundingBox(part.minX(), part.minY(), part.minZ(), part.maxX() + 1, part.maxY() + 1, part.maxZ() + 1);
            List<JsonObject> found = new ArrayList<>();
            // Entities load separately from blocks; Chunk.getEntities() waits for them, getNearbyEntities() does not.
            for (Entity e : w.getChunkAt(part.minX() >> 4, part.minZ() >> 4).getEntities()) {
                if (!bb.contains(e.getLocation().toVector())) continue;
                if (type.isPresent() && !e.getType().getKey().toString().equals(type.get())) continue;
                found.add(entityJson(e));
            }
            return found;
        }).thenApply(parts -> {
            JsonArray list = new JsonArray();
            int total = 0;
            for (List<JsonObject> part : parts) {
                for (JsonObject e : part) {
                    total++;
                    if (list.size() < limit) list.add(e);
                }
            }
            JsonObject r = new JsonObject();
            r.add("entities", list);
            r.addProperty("truncated", total > limit);
            return (JsonElement) r;
        });
    }

    private static JsonElement players(Predicate<String> isBot) {
        JsonArray list = new JsonArray();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            JsonObject o = entityJson(pl);
            o.addProperty("world", pl.getWorld().getName());
            o.addProperty("gameMode", pl.getGameMode().name().toLowerCase(Locale.ROOT));
            o.addProperty("health", pl.getHealth());
            o.addProperty("op", pl.isOp());
            o.addProperty("bot", isBot.test(pl.getName()));
            list.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("players", list);
        return r;
    }

    private static JsonObject entityJson(Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", e.getUniqueId().toString());
        o.addProperty("type", e.getType().getKey().toString());
        o.addProperty("name", e.getName());
        o.addProperty("x", round(e.getLocation().getX()));
        o.addProperty("y", round(e.getLocation().getY()));
        o.addProperty("z", round(e.getLocation().getZ()));
        o.addProperty("yaw", round(e.getLocation().getYaw()));
        o.addProperty("pitch", round(e.getLocation().getPitch()));
        return o;
    }

    private static CompletableFuture<JsonElement> findBlock(JsonObject p, Sync sync) {
        World w = Args.world(p);
        Box requested = Box.from(p);
        BlockMatcher match = BlockMatcher.parse(Args.string(p, "block"));
        int limit = Args.optInt(p, "limit").orElse(100);
        Box box = requested.clampY(w.getMinHeight(), w.getMaxHeight() - 1);
        if (box == null) return CompletableFuture.completedFuture(findResult(List.of(), limit));
        if (box.volume() > MAX_SCAN) {
            throw new AgentError("QUERY_TOO_LARGE", "find_block would scan " + box.volume() + " blocks; the limit is " + MAX_SCAN,
                    "Search a smaller box.");
        }
        return ChunkWork.forEachChunk(sync, w, box, part -> scan(w, part, match, limit))
                .thenApply(parts -> findResult(parts, limit));
    }

    private static List<JsonObject> scan(World w, Box part, BlockMatcher match, int limit) {
        ChunkSnapshot snap = w.getChunkAt(part.minX() >> 4, part.minZ() >> 4).getChunkSnapshot(false, false, false);
        List<JsonObject> hits = new ArrayList<>();
        for (int y = part.minY(); y <= part.maxY(); y++) {
            for (int z = part.minZ(); z <= part.maxZ(); z++) {
                for (int x = part.minX(); x <= part.maxX(); x++) {
                    BlockData d = snap.getBlockData(x & 15, y, z & 15);
                    if (!match.test(d)) continue;
                    JsonObject hit = new JsonObject();
                    hit.addProperty("x", x);
                    hit.addProperty("y", y);
                    hit.addProperty("z", z);
                    hit.addProperty("block", d.getAsString());
                    hits.add(hit);
                    if (hits.size() > limit) return hits;   // one extra tells the caller there were more
                }
            }
        }
        return hits;
    }

    private static JsonElement findResult(List<List<JsonObject>> parts, int limit) {
        JsonArray matches = new JsonArray();
        int total = 0;
        for (List<JsonObject> part : parts) {
            for (JsonObject hit : part) {
                total++;
                if (matches.size() < limit) matches.add(hit);
            }
        }
        JsonObject r = new JsonObject();
        r.add("matches", matches);
        r.addProperty("truncated", total > limit);
        return r;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
