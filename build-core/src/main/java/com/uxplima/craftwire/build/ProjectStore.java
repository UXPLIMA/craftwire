package com.uxplima.craftwire.build;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Projects on disk, so undo and clear survive a restart. Block maps are deflated (magic, state names, then position +
 * name index per entry); states are stored by name because numeric ids change between versions.
 */
public final class ProjectStore {
    private static final int MAGIC = 0x43574231;   // "CWB1"
    private static final Gson GSON = new Gson();

    private final Path root;
    private final StatePalette palette;
    private final List<String> warnings = new ArrayList<>();

    public ProjectStore(Path root, StatePalette palette) {
        this.root = root;
        this.palette = palette;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public synchronized void save(Project p) throws IOException {
        Path dir = root.resolve(p.name());
        Files.createDirectories(dir.resolve("parts"));
        Files.createDirectories(dir.resolve("history"));
        JsonObject j = new JsonObject();
        j.addProperty("name", p.name());
        j.addProperty("world", p.world());
        Transform f = p.frame();
        JsonArray frame = new JsonArray();
        frame.add(f.ox()); frame.add(f.oy()); frame.add(f.oz()); frame.add(f.rot()); frame.add(f.mirrorX() ? 1 : 0);
        j.add("frame", frame);
        JsonArray parts = new JsonArray();
        JsonObject sources = new JsonObject();
        for (String part : p.parts()) {
            parts.add(part);
            writeMap(dir.resolve("parts").resolve(part + ".bin"), p.part(part));
            Project.Source s = p.source(part);
            if (s != null) sources.add(part, source(s));
        }
        j.add("parts", parts);
        j.add("sources", sources);
        JsonArray hist = new JsonArray();
        List<Project.History> oldestFirst = new ArrayList<>(p.history());
        java.util.Collections.reverse(oldestFirst);
        for (Project.History h : oldestFirst) {
            JsonObject e = new JsonObject();
            e.addProperty("seq", h.seq());
            e.addProperty("part", h.part());
            e.addProperty("existed", h.previous() != null);
            if (h.previousSource() != null) e.add("source", source(h.previousSource()));
            hist.add(e);
            Path file = dir.resolve("history").resolve(h.seq() + ".bin");
            if (h.previous() != null && !Files.exists(file)) writeMap(file, h.previous());
        }
        j.add("history", hist);
        // Remove part files of parts that are gone and history files no longer listed (undone or dropped steps).
        try (Stream<Path> s = Files.list(dir.resolve("parts"))) {
            for (Path file : s.toList()) if (!p.parts().contains(file.getFileName().toString().replace(".bin", ""))) Files.delete(file);
        }
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (Project.History h : oldestFirst) listed.add(h.seq() + ".bin");
        try (Stream<Path> s = Files.list(dir.resolve("history"))) {
            for (Path file : s.toList()) if (!listed.contains(file.getFileName().toString())) Files.delete(file);
        }
        writeMap(dir.resolve("original.bin"), p.original());
        writeAtomically(dir.resolve("project.json"), GSON.toJson(j).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject source(Project.Source s) {
        JsonObject o = new JsonObject();
        o.addProperty("code", s.code());
        if (s.file() != null) o.addProperty("file", s.file());
        return o;
    }

    private static Project.Source source(JsonObject o) {
        return o == null ? null : new Project.Source(o.get("code").getAsString(), o.has("file") ? o.get("file").getAsString() : null);
    }

    public synchronized List<Project> loadAll() throws IOException {
        List<Project> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(d -> Files.isRegularFile(d.resolve("project.json"))).sorted().toList()) out.add(load(dir));
        }
        return out;
    }

    private Project load(Path dir) throws IOException {
        JsonObject j = GSON.fromJson(Files.readString(dir.resolve("project.json")), JsonObject.class);
        JsonArray f = j.getAsJsonArray("frame");
        Project p = new Project(j.get("name").getAsString(), j.get("world").getAsString(),
                new Transform(f.get(0).getAsInt(), f.get(1).getAsInt(), f.get(2).getAsInt(), f.get(3).getAsInt(), f.get(4).getAsInt() == 1));
        JsonObject sources = j.getAsJsonObject("sources");
        for (var e : j.getAsJsonArray("parts")) {
            String part = e.getAsString();
            p.restorePart(part, readMap(dir.resolve("parts").resolve(part + ".bin")), source(sources.getAsJsonObject(part)));
        }
        readMap(dir.resolve("original.bin")).forEach(p::recordOriginal);
        List<Project.History> hist = new ArrayList<>();
        for (var e : j.getAsJsonArray("history")) {
            JsonObject h = e.getAsJsonObject();
            long seq = h.get("seq").getAsLong();
            LongIntMap prev = h.get("existed").getAsBoolean() ? readMap(dir.resolve("history").resolve(seq + ".bin")) : null;
            hist.add(new Project.History(seq, h.get("part").getAsString(), prev, source(h.getAsJsonObject("source"))));
        }
        p.restoreHistory(hist);
        return p;
    }

    public synchronized void delete(String name) throws IOException {
        Path dir = root.resolve(name);
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path file : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    /** Drops the oldest undo steps, across projects, until everything fits in maxBytes. Parts and originals stay. */
    public synchronized int enforceLimit(long maxBytes, Map<String, Project> live) throws IOException {
        int dropped = 0;
        while (size() > maxBytes) {
            Project oldestOwner = null;
            Project.History oldest = null;
            for (Project p : live.values()) {
                Project.History h = p.history().peekLast();
                if (h != null && (oldest == null || h.seq() < oldest.seq())) {
                    oldest = h;
                    oldestOwner = p;
                }
            }
            if (oldest == null) break;
            oldestOwner.history().pollLast();
            Files.deleteIfExists(root.resolve(oldestOwner.name()).resolve("history").resolve(oldest.seq() + ".bin"));
            save(oldestOwner);
            dropped++;
        }
        return dropped;
    }

    private long size() throws IOException {
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> s = Files.walk(root)) {
            long n = 0;
            for (Path p : s.filter(Files::isRegularFile).toList()) n += Files.size(p);
            return n;
        }
    }

    private void writeMap(Path file, LongIntMap map) throws IOException {
        Map<Integer, Integer> index = new HashMap<>();
        List<String> names = new ArrayList<>();
        map.forEach((pos, s) -> index.computeIfAbsent(s, k -> {
            names.add(palette.name(k));
            return names.size() - 1;
        }));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeInt(names.size());
            for (String n : names) out.writeUTF(n);
            out.writeInt(map.size());
            try {
                map.forEach((pos, s) -> {
                    try {
                        out.writeLong(pos);
                        out.writeInt(index.get(s));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private LongIntMap readMap(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC) throw new IOException(file + " is not a Craftwire build file");
            int[] ids = new int[in.readInt()];
            for (int i = 0; i < ids.length; i++) {
                String name = in.readUTF();
                try {
                    ids[i] = palette.id(name);
                } catch (IllegalArgumentException e) {
                    warnings.add(file + ": " + name + " no longer exists; loaded as air");
                    ids[i] = palette.air();
                }
            }
            int n = in.readInt();
            LongIntMap m = new LongIntMap(n);
            for (int i = 0; i < n; i++) {
                long pos = in.readLong();
                m.put(pos, ids[in.readInt()]);
            }
            return m;
        }
    }

    private static void writeAtomically(Path file, byte[] data) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
