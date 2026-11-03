package com.uxplima.craftwire.bot.resourcepack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads offered resource packs the way the vanilla client does (one GET, SHA-1 compared with the offered hash,
 * at most 250 MiB as in DownloadedPackSource) and reports 404s, timeouts and hash mismatches. One download per
 * (url, hash) for all bots of the process: a thousand bots joining a pack server fetch the pack once. Files are
 * kept under {@code <cache>/<sha1>.zip}. Thread-safe; downloads run on virtual threads.
 */
public final class PackVerifier implements AutoCloseable {
    /** The vanilla client's limit (DownloadedPackSource.MAX_PACK_SIZE_BYTES). */
    public static final long MAX_SIZE = 250L * 1024 * 1024;

    private final Path cache;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, CompletableFuture<PackCheck>> checks = new ConcurrentHashMap<>();

    public PackVerifier(Path cache) {
        this.cache = cache;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor).build();
    }

    /** The check of one pack; the first caller downloads, everyone else gets the same future. */
    public CompletableFuture<PackCheck> verify(String url, String hash) {
        String expected = hash == null ? "" : hash.toLowerCase(Locale.ROOT);
        return checks.computeIfAbsent(url + "#" + expected, k -> CompletableFuture.supplyAsync(() -> download(url, expected), executor));
    }

    private PackCheck download(String url, String expected) {
        long start = System.currentTimeMillis();
        URI uri;
        try {
            uri = new URI(url);
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                return new PackCheck("invalid_url", null, 0, 0, "only http and https URLs load");
            }
        } catch (URISyntaxException e) {
            return new PackCheck("invalid_url", null, 0, 0, e.getMessage());
        }
        if (!expected.isEmpty()) {
            Path cached = cache.resolve(expected + ".zip");
            if (Files.isRegularFile(cached)) return new PackCheck("ok", expected, size(cached), System.currentTimeMillis() - start, "cached");
        }
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent", "Craftwire bot").GET().build();
        try {
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                if (res.statusCode() / 100 != 2) return new PackCheck("http_" + res.statusCode(), null, 0, System.currentTimeMillis() - start, null);
                return store(in, expected, start);
            }
        } catch (HttpTimeoutException e) {
            return new PackCheck("timeout", null, 0, System.currentTimeMillis() - start, e.getMessage());
        } catch (IOException e) {
            return new PackCheck("error", null, 0, System.currentTimeMillis() - start, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PackCheck("error", null, 0, System.currentTimeMillis() - start, "interrupted");
        }
    }

    /** Streams the body into a temporary file while hashing it, then moves it to its hash name. */
    private PackCheck store(InputStream in, String expected, long start) throws IOException {
        Files.createDirectories(cache);
        Path tmp = Files.createTempFile(cache, "pack", ".part");
        MessageDigest sha1 = sha1();
        long size = 0;
        try (OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                size += n;
                if (size > MAX_SIZE) {
                    return new PackCheck("too_large", null, size, System.currentTimeMillis() - start, "over " + MAX_SIZE + " bytes");
                }
                sha1.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        } finally {
            if (size > MAX_SIZE) Files.deleteIfExists(tmp);
        }
        String actual = HexFormat.of().formatHex(sha1.digest());
        long ms = System.currentTimeMillis() - start;
        if (!expected.isEmpty() && !expected.equals(actual)) {
            Files.deleteIfExists(tmp);
            return new PackCheck("hash_mismatch", actual, size, ms, "offered " + expected);
        }
        Files.move(tmp, cache.resolve(actual + ".zip"), StandardCopyOption.REPLACE_EXISTING);
        return new PackCheck("ok", actual, size, ms, null);
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No SHA-1 in this JVM", e);
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Forgets finished checks, so a fixed pack server is downloaded again. */
    public void forget() {
        checks.values().removeIf(CompletableFuture::isDone);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        http.close();
    }
}
