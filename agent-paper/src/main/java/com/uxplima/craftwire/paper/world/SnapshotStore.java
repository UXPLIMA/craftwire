package com.uxplima.craftwire.paper.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

/**
 * Saves areas as vanilla structure files so they can be put back exactly; keeps the newest {@link #KEEP}. A snapshot
 * is one file per chunk the box touches, each saved and restored on the thread that owns that chunk (on Folia the
 * chunks of a large box can belong to different regions). Snapshots from before 0.9 are one file for the whole box.
 */
public final class SnapshotStore {
    static final int KEEP = 20;

    /** `parts`: one file per chunk; otherwise a single file for the whole box (before 0.9). */
    public record Snapshot(String id, String world, Box box, boolean parts) {}

    private final Path dir;
    private final AtomicLong counter = new AtomicLong();

    public SnapshotStore(Path dir) {
        this.dir = dir;
    }

    /** A fresh snapshot id; save its parts with {@link #savePart}, then {@link #finish} it. */
    public String newId() {
        return "snap-" + System.currentTimeMillis() + "-" + counter.incrementAndGet();
    }

    /** Saves the part of a snapshot inside one chunk. Run on the thread that owns that chunk, with it loaded. */
    public void savePart(String id, World world, Box part) throws IOException {
        Files.createDirectories(dir);
        Bukkit.getStructureManager().saveStructure(partFile(id, part).toFile(), capture(world, part, false));
    }

    /** Records the snapshot once every part is saved; until then {@link #find} does not know it. */
    public void finish(String id, World world, Box box) throws IOException {
        JsonObject meta = box.toJson();
        meta.addProperty("world", world.getName());
        meta.addProperty("parts", true);
        Files.writeString(dir.resolve(id + ".json"), meta.toString());
        prune();
    }

    public Snapshot find(String id) {
        Path meta = dir.resolve(id + ".json");
        if (!id.matches("snap-\\d+-\\d+") || !Files.isRegularFile(meta)) {
            throw new AgentError("SNAPSHOT_NOT_FOUND", "No snapshot " + id,
                    "Use an id returned by world_edit (snapshot or a large edit); only the newest " + KEEP + " are kept.");
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
            return new Snapshot(id, o.get("world").getAsString(), Box.from(o), o.has("parts") && o.get("parts").getAsBoolean());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Puts one chunk's part back. Run on the thread that owns that chunk, with it loaded. */
    public void restorePart(Snapshot snap, World world, Box part) throws IOException {
        place(partFile(snap.id(), part), world, part);
    }

    /** Puts a whole-box snapshot (before 0.9) back. Run on the thread that owns the box, with its chunks loaded. */
    public void restoreWhole(Snapshot snap, World world) throws IOException {
        place(dir.resolve(snap.id() + ".nbt"), world, snap.box());
    }

    private static void place(Path file, World world, Box b) throws IOException {
        Structure s = Bukkit.getStructureManager().loadStructure(file.toFile());
        s.place(new Location(world, b.minX(), b.minY(), b.minZ()), false, StructureRotation.NONE, Mirror.NONE, 0, 1f, new Random());
    }

    private Path partFile(String id, Box part) {
        return dir.resolve(id + "." + Math.floorDiv(part.minX(), 16) + "." + Math.floorDiv(part.minZ(), 16) + ".nbt");
    }

    /** A structure of every block in the box (air included, so placing it back clears what was added). */
    public static Structure capture(World world, Box box, boolean entities) {
        Structure s = Bukkit.getStructureManager().createStructure();
        s.fill(new Location(world, box.minX(), box.minY(), box.minZ()), new BlockVector(box.sizeX(), box.sizeY(), box.sizeZ()), entities);
        return s;
    }

    private void prune() throws IOException {
        List<Path> metas;
        try (Stream<Path> s = Files.list(dir)) {
            metas = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(SnapshotStore::modified))
                    .toList();
        }
        for (int i = 0; i < metas.size() - KEEP; i++) {
            String base = metas.get(i).getFileName().toString().replace(".json", "");
            Files.deleteIfExists(metas.get(i));
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(f -> f.getFileName().toString().startsWith(base + ".")).toList()) Files.deleteIfExists(f);
            }
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
