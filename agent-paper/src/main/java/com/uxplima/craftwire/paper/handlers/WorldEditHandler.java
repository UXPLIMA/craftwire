package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.AgentConfig;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.Sync;
import com.uxplima.craftwire.paper.world.BlockMatcher;
import com.uxplima.craftwire.paper.world.Blocks;
import com.uxplima.craftwire.paper.world.Box;
import com.uxplima.craftwire.paper.world.ChunkWork;
import com.uxplima.craftwire.paper.world.Placement;
import com.uxplima.craftwire.paper.world.SnapshotStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

final class WorldEditHandler {
    static final long AUTO_SNAPSHOT = 32_768;
    static final int MAX_SET_BLOCKS = 10_000;

    private record Placed(int x, int y, int z, BlockData data) {}

    private WorldEditHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, CraftwirePlugin plugin) {
        AgentConfig config = plugin.agentConfig();
        config.require(config.allowWorldEdit(), "allow-world-edit");
        Sync sync = plugin.sync();
        SnapshotStore snaps = plugin.snapshots();
        String action = Args.string(p, "action");
        CompletableFuture<JsonElement> done = switch (action) {
            case "set_blocks" -> setBlocks(p, sync);
            case "fill" -> fill(p, sync, snaps, config);
            case "snapshot" -> snapshot(p, sync, snaps, config);
            case "restore" -> restore(p, sync, snaps);
            case "save_schematic" -> saveSchematic(p, sync, plugin.structuresDir(), config);
            case "paste_schematic" -> pasteSchematic(p, sync, snaps, plugin.structuresDir(), config);
            default -> throw Args.invalid("Unknown action: " + action);
        };
        // A schematic is one structure: on Folia its box must lie in one region.
        return done.exceptionallyCompose(t -> CompletableFuture.failedFuture(Sync.explainThread(t)));
    }

    private static void checkVolume(Box box, AgentConfig config) {
        if (box.volume() > config.maxEditVolume()) {
            throw new AgentError("EDIT_TOO_LARGE", "The edit covers " + box.volume() + " blocks; max-edit-volume is " + config.maxEditVolume(),
                    "Split it into smaller boxes, or raise max-edit-volume in plugins/Craftwire/config.yml.");
        }
    }

    private static void checkHeight(World w, int y) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) {
            throw Args.invalid("y=" + y + " is outside the build height " + w.getMinHeight() + ".." + (w.getMaxHeight() - 1));
        }
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static CompletableFuture<JsonElement> setBlocks(JsonObject p, Sync sync) {
        World w = Args.world(p);
        boolean physics = Args.bool(p, "physics", false);
        JsonArray list = Args.array(p, "blocks");
        if (list.size() > MAX_SET_BLOCKS) throw Args.invalid("At most " + MAX_SET_BLOCKS + " blocks per call");
        Map<String, BlockData> parsed = new HashMap<>();
        Map<Long, List<Placed>> byChunk = new LinkedHashMap<>();
        for (JsonElement e : list) {
            JsonObject b = e.getAsJsonObject();
            int x = Args.integer(b, "x"), y = Args.integer(b, "y"), z = Args.integer(b, "z");
            checkHeight(w, y);
            BlockData data = parsed.computeIfAbsent(Args.string(b, "block"), Blocks::parse);
            byChunk.computeIfAbsent(chunkKey(x >> 4, z >> 4), k -> new ArrayList<>()).add(new Placed(x, y, z, data));
        }
        List<int[]> chunks = byChunk.keySet().stream().map(k -> new int[] {(int) (k >> 32), (int) k.longValue()}).toList();
        return ChunkWork.forChunks(sync, w, chunks, (cx, cz) -> {
            List<Placed> here = byChunk.get(chunkKey(cx, cz));
            for (Placed b : here) w.getBlockAt(b.x(), b.y(), b.z()).setBlockData(b.data(), physics);
            return here.size();
        }).thenApply(counts -> {
            JsonObject r = new JsonObject();
            r.addProperty("changed", counts.stream().mapToInt(Integer::intValue).sum());
            return (JsonElement) r;
        });
    }

    private static CompletableFuture<JsonElement> fill(JsonObject p, Sync sync, SnapshotStore snaps, AgentConfig config) {
        World w = Args.world(p);
        Box requested = Box.from(p);
        checkVolume(requested, config);
        Box box = requested.clampY(w.getMinHeight(), w.getMaxHeight() - 1);
        if (box == null) throw Args.invalid("The box is outside the build height " + w.getMinHeight() + ".." + (w.getMaxHeight() - 1));
        BlockData data = Blocks.parse(Args.string(p, "block"));
        Optional<BlockMatcher> only = Args.optString(p, "replace").map(BlockMatcher::parse);
        boolean physics = Args.bool(p, "physics", false);
        CompletableFuture<String> snapshot = box.volume() > AUTO_SNAPSHOT ? takeSnapshot(sync, snaps, w, box) : CompletableFuture.completedFuture(null);
        return snapshot.thenCompose(id -> ChunkWork.forEachChunk(sync, w, box, part -> {
            int n = 0;
            for (int y = part.minY(); y <= part.maxY(); y++) {
                for (int z = part.minZ(); z <= part.maxZ(); z++) {
                    for (int x = part.minX(); x <= part.maxX(); x++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (only.isPresent() && !only.get().test(b.getBlockData())) continue;
                        b.setBlockData(data, physics);
                        n++;
                    }
                }
            }
            return n;
        }).thenApply(counts -> {
            JsonObject r = box.toJson();
            r.addProperty("changed", counts.stream().mapToInt(Integer::intValue).sum());
            r.addProperty("volume", box.volume());
            if (id != null) r.addProperty("snapshotId", id);
            return (JsonElement) r;
        }));
    }

    /** Copies the box chunk by chunk, each part on the thread that owns its chunk. */
    private static CompletableFuture<String> takeSnapshot(Sync sync, SnapshotStore snaps, World w, Box box) {
        String id = snaps.newId();
        return ChunkWork.forEachChunk(sync, w, box, part -> {
            try {
                snaps.savePart(id, w, part);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return 0;
        }).thenApply(v -> {
            try {
                snaps.finish(id, w, box);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return id;
        });
    }

    private static CompletableFuture<JsonElement> snapshot(JsonObject p, Sync sync, SnapshotStore snaps, AgentConfig config) {
        World w = Args.world(p);
        Box box = Box.from(p);
        checkVolume(box, config);
        return takeSnapshot(sync, snaps, w, box).thenApply(id -> {
            JsonObject r = box.toJson();
            r.addProperty("id", id);
            r.addProperty("volume", box.volume());
            return (JsonElement) r;
        });
    }

    private static CompletableFuture<JsonElement> restore(JsonObject p, Sync sync, SnapshotStore snaps) {
        SnapshotStore.Snapshot snap = snaps.find(Args.string(p, "id"));
        World w = Bukkit.getWorld(snap.world());
        if (w == null) throw new AgentError("WORLD_NOT_FOUND", "World " + snap.world() + " is not loaded", "Load that world, then retry.");
        Box box = snap.box();
        JsonObject r = box.toJson();
        r.addProperty("restored", snap.id());
        if (!snap.parts()) {
            return ChunkWork.forEachChunk(sync, w, box, part -> 0)
                    .thenCompose(v -> sync.region(w, box.minX() >> 4, box.minZ() >> 4, () -> {
                        snaps.restoreWhole(snap, w);
                        return (JsonElement) r;
                    }));
        }
        return ChunkWork.forEachChunk(sync, w, box, part -> {
            try {
                snaps.restorePart(snap, w, part);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return 0;
        }).thenApply(v -> (JsonElement) r);
    }

    private static CompletableFuture<JsonElement> saveSchematic(JsonObject p, Sync sync, Path dir, AgentConfig config) {
        String name = Placement.checkName(Args.string(p, "name"));
        World w = Args.world(p);
        Box box = Box.from(p);
        checkVolume(box, config);
        boolean entities = Args.bool(p, "includeEntities", false);
        Path file = dir.resolve(name + ".nbt");
        return ChunkWork.forEachChunk(sync, w, box, part -> 0)
                .thenCompose(v -> sync.region(w, box.minX() >> 4, box.minZ() >> 4, () -> {
                    Files.createDirectories(dir);
                    Bukkit.getStructureManager().saveStructure(file.toFile(), SnapshotStore.capture(w, box, entities));
                    JsonObject r = box.toJson();
                    r.addProperty("name", name);
                    r.addProperty("path", file.toAbsolutePath().toString());
                    return (JsonElement) r;
                }));
    }

    private static CompletableFuture<JsonElement> pasteSchematic(JsonObject p, Sync sync, SnapshotStore snaps, Path dir, AgentConfig config) {
        String name = Placement.checkName(Args.string(p, "name"));
        Path file = dir.resolve(name + ".nbt");
        if (!Files.isRegularFile(file)) {
            throw new AgentError("SCHEMATIC_NOT_FOUND", "No saved schematic named " + name,
                    "Save one first with world_edit {action:'save_schematic'}; files live in plugins/Craftwire/structures/.");
        }
        World w = Args.world(p);
        JsonObject at = Args.object(p, "at");
        int ax = Args.integer(at, "x"), ay = Args.integer(at, "y"), az = Args.integer(at, "z");
        StructureRotation rotation = Placement.rotation(Args.optString(p, "rotation").orElse("none"));
        Mirror mirror = Placement.mirror(Args.optString(p, "mirror").orElse("none"));
        boolean entities = Args.bool(p, "includeEntities", false);
        Structure structure;
        try {
            structure = Bukkit.getStructureManager().loadStructure(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        BlockVector size = structure.getSize();
        Box box = Placement.footprint(ax, ay, az, size.getBlockX(), size.getBlockY(), size.getBlockZ(), rotation, mirror);
        checkVolume(box, config);
        CompletableFuture<String> snapshot = box.volume() > AUTO_SNAPSHOT ? takeSnapshot(sync, snaps, w, box) : CompletableFuture.completedFuture(null);
        return snapshot.thenCompose(id -> ChunkWork.forEachChunk(sync, w, box, part -> 0)
                .thenCompose(v -> sync.region(w, ax >> 4, az >> 4, () -> {
                    structure.place(new Location(w, ax, ay, az), entities, rotation, mirror, 0, 1f, new Random());
                    JsonObject r = box.toJson();
                    r.addProperty("name", name);
                    if (id != null) r.addProperty("snapshotId", id);
                    return (JsonElement) r;
                })));
    }
}
