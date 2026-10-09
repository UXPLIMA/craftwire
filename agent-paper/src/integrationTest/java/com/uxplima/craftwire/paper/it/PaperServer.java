package com.uxplima.craftwire.paper.it;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** A Paper server process in a fresh flat world, bound to 127.0.0.1. */
final class PaperServer {
    final Process process;
    final List<String> console = new CopyOnWriteArrayList<>();
    private final Writer stdin;

    private PaperServer(Process process) {
        this.process = process;
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) console.add(line);
            } catch (IOException ignored) {
                // the process ended
            }
        }, "paper-console");
        reader.setDaemon(true);
        reader.start();
    }

    static PaperServer start(Path dir, Path paperJar, List<Path> plugins, Path craftwireHome, int port) throws IOException {
        Path pluginDir = dir.resolve("plugins");
        Files.createDirectories(pluginDir);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith("world")).toList()) deleteTree(p);
        }
        try (Stream<Path> s = Files.list(pluginDir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) Files.delete(p);
        }
        deleteTree(pluginDir.resolve("Craftwire"));
        for (int i = 0; i < plugins.size(); i++) Files.copy(plugins.get(i), pluginDir.resolve("it-" + i + ".jar"));
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
                "server-ip=127.0.0.1",
                "server-port=" + port,
                "online-mode=false",
                "level-type=minecraft\\:flat",
                "generate-structures=false",
                "spawn-protection=0",
                // No hostile mobs: their hits knock test bots out of place.
                "difficulty=peaceful",
                "view-distance=4",
                "simulation-distance=4",
                "max-players=4",
                "motd=craftwire-it") + "\n");
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx2G", "-jar", paperJar.toAbsolutePath().toString(), "--nogui")
                .directory(dir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("CRAFTWIRE_HOME", craftwireHome.toAbsolutePath().toString());
        return new PaperServer(pb.start());
    }

    /** Whether the server logged that Craftwire could not be enabled. */
    boolean pluginFailed() {
        return console.stream().anyMatch(l -> l.contains("Error occurred while enabling Craftwire"));
    }

    String tail(int lines) {
        return String.join("\n", console.subList(Math.max(0, console.size() - lines), console.size()));
    }

    void stop() {
        if (!process.isAlive()) return;
        try {
            stdin.write("stop\n");
            stdin.flush();
            if (process.waitFor(90, TimeUnit.SECONDS)) return;
        } catch (IOException ignored) {
            // fall through to a forced stop
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process.destroyForcibly();
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
