package com.uxplima.craftwire.botrunner.bedrock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The names of the block entities a Bedrock bot has seen (a chest's or furnace's CustomName), by position: a menu
 * is titled by its block entity, since ContainerOpen carries no title. Geyser puts the Java window title on the
 * fake chest it places for a plugin's menu. The most recent {@value #MAX} positions are kept. Runner loop thread.
 */
final class BedrockNames {
    static final int MAX = 4096;

    private final Map<Long, String> names = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > MAX;
        }
    };

    /** A block entity's NBT at a position (BlockActorData or a chunk's block entity list): its name or none. */
    void blockEntity(int x, int y, int z, Map<String, Object> nbt) {
        Object name = nbt.get("CustomName");
        if (name instanceof String s && !s.isEmpty()) names.put(key(x, y, z), s);
        else names.remove(key(x, y, z));
    }

    /** The block entities of a chunk or sub-chunk; each carries its position in x, y, z. */
    void blockEntities(List<Map<String, Object>> list) {
        for (Map<String, Object> nbt : list) {
            if (nbt.get("x") instanceof Integer x && nbt.get("y") instanceof Integer y && nbt.get("z") instanceof Integer z) {
                blockEntity(x, y, z, nbt);
            }
        }
    }

    /** The name at a position, or null. */
    String at(int x, int y, int z) {
        return names.get(key(x, y, z));
    }

    void clear() {
        names.clear();
    }

    int size() {
        return names.size();
    }

    /** x and z in 26 bits each, y in 12: unique for every position a Bedrock world has. */
    private static long key(int x, int y, int z) {
        return ((long) x & 0x3ffffff) << 38 | ((long) z & 0x3ffffff) << 12 | (y & 0xfff);
    }
}
