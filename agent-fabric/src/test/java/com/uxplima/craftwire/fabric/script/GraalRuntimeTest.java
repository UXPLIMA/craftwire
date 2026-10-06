package com.uxplima.craftwire.fabric.script;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.uxplima.craftwire.core.AgentError;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraalRuntimeTest {
    @TempDir Path dir;
    HttpServer server;
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            byte[] body = files.get(ex.getRequestURI().getPath());
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/maven2/";
    }

    static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    static final byte[] POLYGLOT = "polyglot jar".getBytes(StandardCharsets.UTF_8);
    static final byte[] JS = "js jar".getBytes(StandardCharsets.UTF_8);

    String manifest() throws Exception {
        files.put("/maven2/org/graalvm/polyglot/polyglot/25.0.4/polyglot-25.0.4.jar", POLYGLOT);
        files.put("/maven2/org/graalvm/js/js-language/25.0.4/js-language-25.0.4.jar", JS);
        return "version=25.0.4\n"
                + "org.graalvm.polyglot:polyglot:25.0.4:polyglot-25.0.4.jar " + sha256(POLYGLOT) + "\n"
                + "org.graalvm.js:js-language:25.0.4:js-language-25.0.4.jar " + sha256(JS) + "\n";
    }

    GraalRuntime.Manifest parse(String text) throws IOException {
        return GraalRuntime.Manifest.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsTheManifest() throws Exception {
        GraalRuntime.Manifest m = parse(manifest());
        assertEquals("25.0.4", m.version());
        assertEquals(2, m.artifacts().size());
        assertEquals("org/graalvm/polyglot/polyglot/25.0.4/polyglot-25.0.4.jar", m.artifacts().get(0).path());
    }

    @Test
    void downloadsChecksAndThenReusesTheJars() throws Exception {
        GraalRuntime.Manifest m = parse(manifest());
        List<Path> jars = GraalRuntime.ensure(dir, m, base());
        assertEquals(2, jars.size());
        assertArrayEquals(POLYGLOT, Files.readAllBytes(jars.get(0)));
        int after = requests.get();
        GraalRuntime.ensure(dir, m, base());
        assertEquals(after, requests.get(), "cached jars are not downloaded again");
    }

    @Test
    void refusesAJarWhoseChecksumDiffersAndKeepsNothingOfIt() throws Exception {
        GraalRuntime.Manifest m = parse(manifest());
        files.put("/maven2/org/graalvm/js/js-language/25.0.4/js-language-25.0.4.jar", "tampered".getBytes(StandardCharsets.UTF_8));
        AgentError e = assertThrows(AgentError.class, () -> GraalRuntime.ensure(dir, m, base()));
        assertEquals("DOWNLOAD_FAILED", e.code());
        assertTrue(e.getMessage().contains("js-language"), e.getMessage());
        try (var list = Files.list(dir)) {
            assertTrue(list.noneMatch(p -> p.getFileName().toString().startsWith("js-language")), "no bad or partial file left");
        }
    }

    @Test
    void aCachedJarThatChangedOnDiskIsDownloadedAgain() throws Exception {
        GraalRuntime.Manifest m = parse(manifest());
        List<Path> jars = GraalRuntime.ensure(dir, m, base());
        Files.writeString(jars.get(1), "corrupt");
        GraalRuntime.ensure(dir, m, base());
        assertArrayEquals(JS, Files.readAllBytes(jars.get(1)));
    }

    @Test
    void anUnreachableRepositoryIsAClearError() throws Exception {
        GraalRuntime.Manifest m = parse(manifest());
        server.stop(0);
        AgentError e = assertThrows(AgentError.class, () -> GraalRuntime.ensure(dir, m, base()));
        assertEquals("DOWNLOAD_FAILED", e.code());
        assertTrue(e.hint().contains("network"), e.hint());
    }
}
