package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.codec.BlockPalette;
import com.uxplima.craftwire.bedrock.packet.StartGame;
import com.uxplima.craftwire.bot.path.Cell;
import com.uxplima.craftwire.botrunner.world.BlockClassifier;
import com.uxplima.craftwire.core.AgentError;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The block states Bedrock bots decode chunks with: the vanilla list (pinned in bedrock/bedrock-data.properties,
 * downloaded once into the runner's home and checked by sha256) plus each server's custom blocks, turned into a
 * {@link Palette} per server. Thread-safe; joins call it from their setup threads.
 */
final class BedrockBlocks {
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final Path cache;
    private final Properties pins = new Properties();
    private final Map<String, Palette> palettes = new ConcurrentHashMap<>();
    private volatile byte[] vanilla;

    BedrockBlocks(Path home) {
        this.cache = home.resolve("cache").resolve("bedrock");
        try (InputStream in = BedrockBlocks.class.getResourceAsStream("/bedrock/bedrock-data.properties")) {
            if (in == null) throw new IllegalStateException("the runner jar has no bedrock/bedrock-data.properties");
            pins.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read bedrock/bedrock-data.properties", e);
        }
    }

    /**
     * A server's block palette and classifier. Servers with the same custom blocks and id mode share one, since the
     * runtime ids then match.
     */
    Palette palette(StartGame start) {
        StringBuilder key = new StringBuilder(start.hashedBlockIds() ? "hashed" : "runtime");
        for (StartGame.CustomBlock b : start.customBlocks()) key.append('|').append(b.name()).append(b.properties().hashCode());
        return palettes.computeIfAbsent(key.toString(), k -> build(start));
    }

    private Palette build(StartGame start) {
        List<BlockPalette.State> custom = new ArrayList<>();
        for (StartGame.CustomBlock b : start.customBlocks()) custom.addAll(BlockPalette.customStates(b.name(), b.properties()));
        return new Palette(BlockPalette.load(vanilla(), custom, start.hashedBlockIds()));
    }

    private byte[] vanilla() {
        byte[] v = vanilla;
        if (v != null) return v;
        synchronized (this) {
            if (vanilla == null) vanilla = load();
            return vanilla;
        }
    }

    private byte[] load() {
        String sha = pins.getProperty("blockStates.sha256");
        Path dest = cache.resolve("block_states-" + sha.substring(0, 12) + ".nbt");
        try {
            if (Files.exists(dest)) {
                byte[] have = Files.readAllBytes(dest);
                if (sha.equals(sha256(have))) return have;
            }
            Files.createDirectories(cache);
            HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(pins.getProperty("blockStates.url"))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            String got = sha256(r.body());
            if (!sha.equals(got)) throw new IOException("block_states.nbt sha256 " + got + ", expected " + sha);
            Path part = dest.resolveSibling(dest.getFileName() + ".part");
            Files.write(part, r.body());
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return r.body();
        } catch (IOException e) {
            throw new AgentError("BEDROCK_DATA_UNAVAILABLE", "Could not get the Bedrock block list: " + e.getMessage(),
                    "The runner downloads it once from " + pins.getProperty("blockStates.url") + "; check the network, or put the file at "
                            + dest + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentError("BEDROCK_DATA_UNAVAILABLE", "Interrupted while downloading the Bedrock block list", "Spawn again.");
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK has no SHA-256", e);
        }
    }

    /**
     * A palette as the shared world stores it: dense ids are runtime id + 1 with air at 0 (ChunkColumn's empty
     * value), so a chunk's network ids map to chars. Immutable.
     */
    static final class Palette implements BlockClassifier {
        static final int UNKNOWN = 0xffff;

        private final BlockPalette states;
        private final Cell[] cells;
        private final int air;

        Palette(BlockPalette states) {
            this.states = states;
            this.cells = new Cell[states.size()];
            int airId = -1;
            for (int i = 0; i < states.size(); i++) {
                BlockPalette.State s = states.state(i);
                cells[i] = BedrockCells.classify(s);
                if (s.name().equals("minecraft:air")) airId = i;
            }
            this.air = airId;
        }

        /** The dense id of a network id (a runtime id or, in hashed mode, a state hash). */
        int dense(int networkId) {
            int rid = states.runtimeId(networkId);
            if (rid < 0) return UNKNOWN;
            return rid == air ? 0 : rid + 1;
        }

        /** The runtime id of a dense id, or -1. */
        int runtimeId(int dense) {
            if (dense == 0) return air;
            return dense == UNKNOWN ? -1 : dense - 1;
        }

        BlockPalette.State state(int dense) {
            int rid = runtimeId(dense);
            return rid < 0 ? null : states.state(rid);
        }

        @Override
        public Cell classify(int dense) {
            if (dense == 0) return Cell.AIR;
            int rid = dense - 1;
            return rid >= 0 && rid < cells.length ? cells[rid] : Cell.UNKNOWN;
        }

        @Override
        public String blockId(int dense) {
            BlockPalette.State s = state(dense);
            return s == null ? "minecraft:unknown" : s.name();
        }
    }
}
