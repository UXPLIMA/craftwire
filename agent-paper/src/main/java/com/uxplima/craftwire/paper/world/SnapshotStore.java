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

/** Saves areas as vanilla structure files so they can be put back exactly; keeps the newest {@link #KEEP}. */
public final class SnapshotStore {
    static final int KEEP = 20;

    public record Snapshot(String id, String world, Box box) {}

    private final Path dir;
    private final AtomicLong counter = new AtomicLong();

    public SnapshotStore(Path dir) {
        this.dir = dir;
    }

    /** Run on the thread that owns the box, with its chunks loaded. */
    public String save(World world, Box box) throws IOException {
        Files.createDirectories(dir);
        String id = "snap-" + System.currentTimeMillis() + "-" + counter.incrementAndGet();
        Bukkit.getStructureManager().saveStructure(dir.resolve(id + ".nbt").toFile(), capture(world, box, false));
        JsonObject meta = box.toJson();
        meta.addProperty("world", world.getName());
        Files.writeString(dir.resolve(id + ".json"), meta.toString());
        prune();
        return id;
    }

    public Snapshot find(String id) {
        Path meta = dir.resolve(id + ".json");
        if (!id.matches("snap-\\d+-\\d+") || !Files.isRegularFile(meta)) {
            throw new AgentError("SNAPSHOT_NOT_FOUND", "No snapshot " + id,
                    "Use an id returned by world_edit (snapshot or a large edit); only the newest " + KEEP + " are kept.");
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
            return new Snapshot(id, o.get("world").getAsString(), Box.from(o));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Run on the thread that owns the box, with its chunks loaded. */
    public void restore(Snapshot snap, World world) throws IOException {
        Structure s = Bukkit.getStructureManager().loadStructure(dir.resolve(snap.id() + ".nbt").toFile());
        Box b = snap.box();
        s.place(new Location(world, b.minX(), b.minY(), b.minZ()), false, StructureRotation.NONE, Mirror.NONE, 0, 1f, new Random());
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
            Files.deleteIfExists(dir.resolve(base + ".nbt"));
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
