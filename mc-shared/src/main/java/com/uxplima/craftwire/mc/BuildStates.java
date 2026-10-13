package com.uxplima.craftwire.mc;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.uxplima.craftwire.build.Axis;
import com.uxplima.craftwire.build.StatePalette;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Build states are the game's own block state ids, so buffers hold plain ints and writes need no lookup. */
public final class BuildStates implements StatePalette {
    public static final BuildStates INSTANCE = new BuildStates();
    private final Map<String, Integer> parsed = new ConcurrentHashMap<>();
    private final int air = Block.getId(Blocks.AIR.defaultBlockState());

    private BuildStates() {}

    public BlockState state(int id) {
        return Block.stateById(id);
    }

    public int id(BlockState s) {
        return Block.getId(s);
    }

    @Override public int id(String spec) {
        return parsed.computeIfAbsent(spec.strip(), s -> {
            try {
                return Block.getId(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState());
            } catch (CommandSyntaxException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        });
    }

    @Override public String name(int id) {
        return BlockStateParser.serialize(Block.stateById(id));
    }

    @Override public int air() {
        return air;
    }

    @Override public boolean isAir(int id) {
        return Block.stateById(id).isAir();
    }

    @Override public int rotate(int id, int quarterTurns) {
        Rotation r = switch (quarterTurns & 3) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        return Block.getId(Block.stateById(id).rotate(r));
    }

    @Override public int mirror(int id, Axis axis) {
        // FRONT_BACK negates x (east <-> west); LEFT_RIGHT negates z (north <-> south).
        return Block.getId(Block.stateById(id).mirror(axis == Axis.X ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT));
    }

    @Override public List<String> suggest(String spec, int max) {
        String s = spec.strip();
        int b = s.indexOf('[');
        String base = (b < 0 ? s : s.substring(0, b)).replace("minecraft:", "");
        return BuiltInRegistries.BLOCK.keySet().stream()
                .map(k -> k.getPath())
                .sorted(Comparator.comparingInt((String k) -> distance(base, k)).thenComparing(k -> k))
                .limit(max).map(k -> "minecraft:" + k).toList();
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
