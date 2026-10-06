package com.uxplima.craftwire.fabric.script;

import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.core.ScriptRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * GraalJS for client_eval, downloaded on first use. The agent jar carries the list of jars and their sha256
 * (generated at build time from the same GraalJS dependency the Paper plugin uses); every jar is checked before it
 * is used. GraalJS and the script engine run in a class loader of their own, so the game never loads them unless
 * client_eval is used.
 */
public final class GraalRuntime {
    public static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
    private static final String MANIFEST = "/craftwire-graaljs.txt";
    private static final String SCRIPT_JAR = "/craftwire/craftwire-script.jar";
    private static final String ENGINE = "com.uxplima.craftwire.script.ScriptEngine";

    private GraalRuntime() {}

    public record Artifact(String group, String module, String version, String file, String sha256) {
        /** Its path in a Maven repository. */
        public String path() {
            return group.replace('.', '/') + "/" + module + "/" + version + "/" + file;
        }
    }

    public record Manifest(String version, List<Artifact> artifacts) {
        static Manifest read(InputStream in) throws IOException {
            String version = null;
            List<Artifact> list = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("version=")) {
                    version = line.substring("version=".length());
                    continue;
                }
                String[] parts = line.split(" ");
                String[] coords = parts[0].split(":");
                if (parts.length != 2 || coords.length != 4) throw new IOException("Bad line in the GraalJS list: " + line);
                list.add(new Artifact(coords[0], coords[1], coords[2], coords[3], parts[1]));
            }
            if (version == null || list.isEmpty()) throw new IOException("The GraalJS list is empty");
            return new Manifest(version, List.copyOf(list));
        }

        public static Manifest bundled() {
            try (InputStream in = GraalRuntime.class.getResourceAsStream(MANIFEST)) {
                if (in == null) throw new IOException(MANIFEST + " is missing from the agent jar");
                return read(in);
            } catch (IOException e) {
                throw new AgentError("EVAL_UNAVAILABLE", "This agent build cannot run scripts: " + e.getMessage(), "Reinstall the Craftwire agent mod.");
            }
        }
    }

    /** The jars in `dir`, downloading the missing or changed ones from `repository`; each checked against its sha256. */
    public static synchronized List<Path> ensure(Path dir, Manifest manifest, String repository) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new AgentError("DOWNLOAD_FAILED", "Cannot create " + dir + ": " + e.getMessage(), "Check that the folder is writable.");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
        List<Path> jars = new ArrayList<>();
        for (Artifact a : manifest.artifacts()) {
            Path file = dir.resolve(a.file());
            if (!matches(file, a.sha256())) download(http, repository + a.path(), file, a);
            jars.add(file);
        }
        return jars;
    }

    private static boolean matches(Path file, String sha256) {
        try {
            return Files.isRegularFile(file) && sha256(Files.readAllBytes(file)).equals(sha256);
        } catch (IOException e) {
            return false;
        }
    }

    private static void download(HttpClient http, String url, Path file, Artifact a) {
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try {
            HttpResponse<Path> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "craftwire (https://github.com/uxplima/craftwire)").build(), HttpResponse.BodyHandlers.ofFile(part));
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " for " + url);
            String got = sha256(Files.readAllBytes(part));
            if (!got.equals(a.sha256())) {
                throw new IOException(a.module() + " " + a.version() + " has sha256 " + got + ", expected " + a.sha256());
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(part);
            deleteQuietly(file);
            throw new AgentError("DOWNLOAD_FAILED", "Could not download GraalJS (" + a.module() + "): " + e.getMessage(),
                    "client_eval downloads GraalJS (about 60 MB) from Maven Central once: check the network, then try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(part);
            throw new AgentError("DOWNLOAD_FAILED", "The GraalJS download was interrupted", "Try again.");
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // left for the next attempt to overwrite
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A script engine running on the downloaded GraalJS. Its class loader sees the game and the mods through its
     * parent (the agent's loader), so scripts reach any class with Java.type.
     */
    public static ScriptRunner load(Path home, String prelude) {
        Manifest manifest = Manifest.bundled();
        Path dir = home.resolve("lib").resolve("graaljs-" + manifest.version());
        List<Path> jars = new ArrayList<>(ensure(dir, manifest, MAVEN_CENTRAL));
        jars.add(scriptJar(dir));
        try {
            URL[] urls = new URL[jars.size()];
            for (int i = 0; i < urls.length; i++) urls[i] = jars.get(i).toUri().toURL();
            URLClassLoader loader = new URLClassLoader("craftwire-graaljs", urls, GraalRuntime.class.getClassLoader());
            Object engine = loader.loadClass(ENGINE).getConstructor(ClassLoader.class, String.class).newInstance(loader, prelude);
            return (ScriptRunner) engine;
        } catch (ReflectiveOperationException | IOException | LinkageError e) {
            throw new AgentError("EVAL_UNAVAILABLE", "GraalJS did not start: " + e, "Report this with the game log.");
        }
    }

    /** The script engine's own jar, shipped inside the agent jar (where the game's class loader does not look). */
    private static Path scriptJar(Path dir) {
        Path out = dir.resolve("craftwire-script.jar");
        try (InputStream in = GraalRuntime.class.getResourceAsStream(SCRIPT_JAR)) {
            if (in == null) throw new IOException(SCRIPT_JAR + " is missing from the agent jar");
            byte[] bytes = in.readAllBytes();
            if (!Files.isRegularFile(out) || !java.util.Arrays.equals(Files.readAllBytes(out), bytes)) {
                Path part = dir.resolve("craftwire-script.jar.part");
                Files.write(part, bytes);
                Files.move(part, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return out;
        } catch (IOException e) {
            throw new AgentError("EVAL_UNAVAILABLE", "This agent build cannot run scripts: " + e.getMessage(), "Reinstall the Craftwire agent mod.");
        }
    }
}
