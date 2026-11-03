package com.uxplima.craftwire.bedrock.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The block states a Bedrock client knows, in runtime id order: the vanilla states (block_states.nbt, concatenated
 * network NBT compounds {name, states, version}) plus the server's custom blocks from StartGame, stable-sorted by
 * the FNV-1 64 hash of the block name. Chunks name blocks by runtime id, or with StartGame's useBlockNetworkIDHashes
 * by the FNV-1a 32 hash of the state's NBT, which this palette maps back to runtime ids. Immutable once built.
 */
public final class BlockPalette {
    /** One block state: its name (minecraft:oak_log) and properties (Byte, Short, Integer or String values). */
    public record State(String name, Map<String, Object> properties) {}

    private static final long FNV64_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV64_PRIME = 0x100000001b3L;
    private static final int FNV32_OFFSET = 0x811c9dc5;
    private static final int FNV32_PRIME = 0x01000193;
    private static final int UNKNOWN_HASH = 0xfffffffe;

    private final List<State> states;
    private final Map<Integer, Integer> byHash;
    private final boolean hashed;

    private BlockPalette(List<State> states, boolean hashed) {
        this.states = List.copyOf(states);
        this.hashed = hashed;
        Map<Integer, Integer> hashes = new HashMap<>();
        if (hashed) for (int i = 0; i < states.size(); i++) hashes.put(networkHash(states.get(i)), i);
        this.byHash = Map.copyOf(hashes);
    }

    /** Builds the palette from the vanilla state file and the server's custom block states. */
    public static BlockPalette load(byte[] vanillaStates, List<State> custom, boolean hashed) {
        List<State> all = new ArrayList<>(readStates(vanillaStates));
        all.addAll(custom);
        all.sort(Comparator.comparing((State s) -> fnv1(s.name()), Long::compareUnsigned));
        return new BlockPalette(all, hashed);
    }

    @SuppressWarnings("unchecked")
    private static List<State> readStates(byte[] file) {
        ByteBuf b = Unpooled.wrappedBuffer(file);
        List<State> out = new ArrayList<>(16384);
        while (b.isReadable()) {
            Map<String, Object> tag = Nbt.readCompound(b, Nbt.Encoding.NETWORK);
            Object states = tag.get("states");
            out.add(new State((String) tag.get("name"), states instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of()));
        }
        return out;
    }

    /**
     * The states of a custom block from its StartGame properties: every combination of its "properties" entries
     * ({name, enum}), first property varying slowest. Without properties the block has one state.
     */
    @SuppressWarnings("unchecked")
    public static List<State> customStates(String name, Map<String, Object> definition) {
        List<State> out = new ArrayList<>();
        out.add(new State(name, Map.of()));
        if (!(definition.get("properties") instanceof List<?> props)) return out;
        for (Object p : props) {
            if (!(p instanceof Map<?, ?> prop) || !(prop.get("name") instanceof String key) || !(prop.get("enum") instanceof List<?> values)) {
                continue;
            }
            List<State> next = new ArrayList<>(out.size() * Math.max(1, values.size()));
            for (State s : out) {
                for (Object v : values) {
                    Map<String, Object> m = new TreeMap<>(s.properties());
                    m.put(key, v);
                    next.add(new State(name, m));
                }
            }
            out = next;
        }
        return out;
    }

    public int size() {
        return states.size();
    }

    /** The state with this runtime id, or null. */
    public State state(int runtimeId) {
        return runtimeId >= 0 && runtimeId < states.size() ? states.get(runtimeId) : null;
    }

    /** The runtime id a chunk or UpdateBlock names (a runtime id, or a network hash in hashed mode); -1 if unknown. */
    public int runtimeId(int networkId) {
        if (!hashed) return networkId >= 0 && networkId < states.size() ? networkId : -1;
        Integer id = byHash.get(networkId);
        return id == null ? -1 : id;
    }

    /** FNV-1 64 of a block name, the key runtime ids are sorted by. */
    static long fnv1(String name) {
        long h = FNV64_OFFSET;
        for (byte c : name.getBytes(StandardCharsets.UTF_8)) {
            h *= FNV64_PRIME;
            h ^= c & 0xff;
        }
        return h;
    }

    /** FNV-1a 32 of the state as a little-endian NBT compound {name, states} with sorted keys. */
    static int networkHash(State s) {
        if (s.name().equals("minecraft:unknown")) return UNKNOWN_HASH;
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        out.write(10);
        out.write(0);
        out.write(0);
        out.write(8);
        writeString(out, "name");
        writeString(out, s.name());
        out.write(10);
        writeString(out, "states");
        for (Map.Entry<String, Object> e : new TreeMap<>(s.properties()).entrySet()) writeProperty(out, e.getKey(), e.getValue());
        out.write(0);
        out.write(0);
        int h = FNV32_OFFSET;
        for (byte c : out.toByteArray()) {
            h ^= c & 0xff;
            h *= FNV32_PRIME;
        }
        return h;
    }

    private static void writeProperty(ByteArrayOutputStream out, String key, Object v) {
        switch (v) {
            case String s -> {
                out.write(8);
                writeString(out, key);
                writeString(out, s);
            }
            case Byte b -> {
                out.write(1);
                writeString(out, key);
                out.write(b);
            }
            case Boolean b -> {
                out.write(1);
                writeString(out, key);
                out.write(b ? 1 : 0);
            }
            case Short s -> {
                out.write(2);
                writeString(out, key);
                out.write(s & 0xff);
                out.write((s >> 8) & 0xff);
            }
            case Integer i -> {
                out.write(3);
                writeString(out, key);
                for (int k = 0; k < 4; k++) out.write((i >> (8 * k)) & 0xff);
            }
            default -> throw new BedrockCodecException("block property " + key + " has an unhashable type " + v.getClass().getSimpleName());
        }
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length & 0xff);
        out.write((bytes.length >> 8) & 0xff);
        out.write(bytes, 0, bytes.length);
    }
}
