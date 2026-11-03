package com.uxplima.craftwire.botrunner.bedrock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Bedrock's block hardness, mining material and harvest tools, and each tool's speed per material, from the tables
 * generated out of minecraft-data (bedrock/block-hardness.tsv and bedrock/tool-speeds.tsv in the runner jar). The
 * protocol carries none of it. Immutable once loaded; any thread.
 */
final class BedrockHardness {
    /** One block: hardness below zero never breaks; `tools` empty means any tool (or a hand) harvests it. */
    record Block(double hardness, String material, Set<String> tools) {}

    private static final Map<String, Block> BLOCKS = new HashMap<>();
    private static final Map<String, Map<String, Double>> SPEEDS = new HashMap<>();

    static {
        lines("/bedrock/block-hardness.tsv", f -> {
            Set<String> tools = f[3].equals("-") ? Set.of() : Set.of(f[3].split(","));
            BLOCKS.put(f[0], new Block(Double.parseDouble(f[1]), f[2], tools));
        });
        lines("/bedrock/tool-speeds.tsv", f -> {
            Map<String, Double> speeds = new HashMap<>();
            if (f.length > 1 && !f[1].isEmpty()) {
                for (String pair : f[1].split(",")) {
                    int eq = pair.indexOf('=');
                    speeds.put(pair.substring(0, eq), Double.parseDouble(pair.substring(eq + 1)));
                }
            }
            SPEEDS.put(f[0], Map.copyOf(speeds));
        });
    }

    private BedrockHardness() {}

    /** A vanilla block by its id without the namespace, or null (custom blocks, names the table lacks). */
    static Block block(String name) {
        return BLOCKS.get(name);
    }

    /** The speed of `tool` (no namespace, null for a hand) on a material: 1 when it does not help. */
    static double speed(String material, String tool) {
        if (tool == null) return 1;
        Map<String, Double> m = SPEEDS.get(material);
        Double s = m == null ? null : m.get(tool);
        return s == null ? 1 : s;
    }

    /** Lines of a bundled table, split at tabs; `#` lines are comments. */
    private static void lines(String resource, Consumer<String[]> row) {
        try (InputStream in = BedrockHardness.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("the runner jar has no " + resource);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                row.accept(line.split("\t", -1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
    }
}
