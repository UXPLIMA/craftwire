package com.uxplima.craftwire.botrunner.java;

import com.uxplima.craftwire.botrunner.bot.RunnerServices;
import com.uxplima.craftwire.core.AgentError;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
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
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The Minecraft versions the runner's Java bots play: per version the vanilla server jar is downloaded once (sha1
 * from versions/<mc>.properties), its bundled jars are unpacked (META-INF/versions and META-INF/libraries), and a
 * class loader with the javabot jars on top bootstraps the registries. Every bot of that version shares it.
 */
final class JavaVersions implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger("craftwire-botrunner");
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final RunnerServices services;
    private final Path dir;
    private final Properties pins = new Properties();
    private final Map<String, JavaBotFactory> loaded = new ConcurrentHashMap<>();

    JavaVersions(RunnerServices services) {
        this.services = services;
        this.dir = services.home().resolve("botrunner").resolve("java");
        try (InputStream in = JavaVersions.class.getResourceAsStream("/javabot/java-versions.properties")) {
            if (in == null) throw new IllegalStateException("the runner jar has no javabot/java-versions.properties");
            pins.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<String> supported() {
        List<String> out = new ArrayList<>();
        for (String k : pins.stringPropertyNames()) if (k.endsWith(".url")) out.add(k.substring(0, k.length() - 4));
        out.sort(String::compareTo);
        return out;
    }

    /** The version's bots, preparing the version on first use (download, unpack, bootstrap: seconds, once). */
    JavaBotFactory get(String version) {
        if (pins.getProperty(version + ".url") == null) {
            throw new AgentError("VERSION_UNSUPPORTED", "Java bots do not play Minecraft " + version,
                    "Supported: " + String.join(", ", supported()) + ".");
        }
        return loaded.computeIfAbsent(version, this::prepare);
    }

    private JavaBotFactory prepare(String version) {
        try {
            Path vdir = dir.resolve(version);
            Path server = download(version, vdir.resolve("server.jar"));
            List<URL> urls = unpack(server, vdir.resolve("unpacked"));
            urls.add(resource("/javabot/javabot-common.jar", vdir.resolve("javabot-common.jar")));
            urls.add(resource("/javabot/javabot-compat-" + version + ".jar", vdir.resolve("javabot-compat.jar")));
            VersionLoader loader = new VersionLoader(version, urls.toArray(URL[]::new), JavaVersions.class.getClassLoader());
            JavaBotFactory f = ServiceLoader.load(JavaBotFactory.class, loader).findFirst()
                    .orElseThrow(() -> new IllegalStateException("javabot-common.jar has no JavaBotFactory service"));
            f.start(services);
            LOG.info("Java bots ready for Minecraft {} (protocol {})", f.minecraftVersion(), f.protocolVersion());
            return f;
        } catch (IOException e) {
            throw new AgentError("VERSION_SETUP_FAILED", "Could not prepare Minecraft " + version + ": " + e.getMessage(),
                    "Check the network and the disk space under " + dir + ", then spawn again.");
        }
    }

    private Path download(String version, Path dest) throws IOException {
        String sha1 = pins.getProperty(version + ".sha1");
        if (Files.exists(dest) && sha1.equals(sha1(dest))) return dest;
        Files.createDirectories(dest.getParent());
        Path part = dest.resolveSibling("server.jar.part");
        LOG.info("Downloading the Minecraft {} server jar for its packet codecs", version);
        try {
            HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(pins.getProperty(version + ".url"))).build(),
                    HttpResponse.BodyHandlers.ofFile(part));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        String got = sha1(part);
        if (!sha1.equals(got)) {
            Files.deleteIfExists(part);
            throw new IOException("server jar sha1 " + got + ", expected " + sha1);
        }
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return dest;
    }

    /** The bundler lists its jars as "sha256\tid\tpath" under META-INF/versions.list and META-INF/libraries.list. */
    private static List<URL> unpack(Path server, Path out) throws IOException {
        List<URL> urls = new ArrayList<>();
        try (JarFile jar = new JarFile(server.toFile())) {
            for (String list : List.of("versions", "libraries")) {
                ZipEntry index = jar.getEntry("META-INF/" + list + ".list");
                if (index == null) throw new IOException("not a bundler jar: no META-INF/" + list + ".list");
                for (String line : new String(jar.getInputStream(index).readAllBytes()).split("\n")) {
                    String[] parts = line.trim().split("\t");
                    if (parts.length < 3) continue;
                    Path target = out.resolve(list).resolve(parts[2]).normalize();
                    if (!target.startsWith(out)) throw new IOException("bundled path leaves the folder: " + parts[2]);
                    if (!Files.exists(target)) {
                        Files.createDirectories(target.getParent());
                        try (InputStream in = jar.getInputStream(jar.getEntry("META-INF/" + list + "/" + parts[2]))) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    urls.add(url(target));
                }
            }
        }
        return urls;
    }

    /** The javabot jars ship inside the runner jar; copied out on every start so an upgrade never runs old code. */
    private static URL resource(String name, Path dest) throws IOException {
        try (InputStream in = JavaVersions.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("the runner jar has no " + name);
            Files.createDirectories(dest.getParent());
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return url(dest);
    }

    private static URL url(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String sha1(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        loaded.values().forEach(JavaBotFactory::close);
    }
}
