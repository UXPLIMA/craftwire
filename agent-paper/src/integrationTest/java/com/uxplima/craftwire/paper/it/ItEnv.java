package com.uxplima.craftwire.paper.it;

import com.google.gson.JsonObject;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** One Paper server + test hub shared by every IT class in the run (startup takes tens of seconds). */
final class ItEnv {
    private static ItEnv instance;
    private static Throwable startFailure;

    final ItHub hub;
    final PaperServer server;
    final JsonObject hello;

    private ItEnv(ItHub hub, PaperServer server, JsonObject hello) {
        this.hub = hub;
        this.server = server;
        this.hello = hello;
    }

    static synchronized ItEnv get() throws Exception {
        // A failed start is not retried: every later test would wait out the same timeout again.
        if (startFailure != null) throw new AssertionError("Paper did not start (see the first failure)", startFailure);
        if (instance == null) {
            try {
                instance = start();
            } catch (Throwable t) {
                startFailure = t;
                throw t;
            }
        }
        return instance;
    }

    static synchronized void shutdown() {
        if (instance == null) return;
        instance.server.stop();
        try {
            instance.hub.stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        instance = null;
    }

    private static ItEnv start() throws Exception {
        Path work = Path.of(System.getProperty("craftwire.itDir"));
        Path home = work.resolve("craftwire-home");
        Files.createDirectories(home);
        ItHub hub = new ItHub(freePort());
        hub.startAndWait();
        Files.writeString(home.resolve("hub.json"), "{\"port\":" + hub.getPort() + ",\"token\":\"" + ItHub.TOKEN + "\"}");
        Path paper = PaperDownload.ensure(work.resolve("cache"), project(), System.getProperty("craftwire.mcVersion"),
                System.getProperty("craftwire.paperBuild"), System.getProperty("craftwire.paperSha256"));
        List<Path> plugins = Arrays.stream(System.getProperty("craftwire.pluginJars").split(File.pathSeparator)).map(Path::of).toList();
        PaperServer server = PaperServer.start(work.resolve("server"), paper, plugins, home, freePort());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try {
            // First run downloads the Mojang jar and GraalJS through Paper's library loader: allow minutes.
            // Stops waiting at once when the server died or Craftwire failed to enable.
            JsonObject hello = hub.awaitHello(300_000, () -> server.process.isAlive() && !server.pluginFailed());
            hub.awaitLog(m -> m.startsWith("Done ("), 300_000);
            return new ItEnv(hub, server, hello);
        } catch (Throwable t) {
            server.stop();
            throw new AssertionError(t.getMessage() + "\n--- Paper console (tail) ---\n" + server.tail(80), t);
        }
    }

    /** "paper" or "folia" (-Pserver=folia). */
    static String project() {
        return System.getProperty("craftwire.serverProject", "paper");
    }

    static boolean folia() {
        return project().equals("folia");
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
