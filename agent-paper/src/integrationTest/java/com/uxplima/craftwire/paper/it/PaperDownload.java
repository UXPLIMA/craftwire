package com.uxplima.craftwire.paper.it;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Downloads one pinned Paper build through the Fill v3 API and checks its sha256. */
final class PaperDownload {
    private static final String AGENT = "craftwire-integration-tests (https://github.com/uxplima/craftwire)";

    private PaperDownload() {}

    static Path ensure(Path cacheDir, String mcVersion, String build, String sha256) throws Exception {
        Path jar = cacheDir.resolve("paper-" + mcVersion + "-" + build + ".jar");
        if (Files.isRegularFile(jar) && sha256(jar).equals(sha256)) return jar;
        Files.createDirectories(cacheDir);
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        URI metaUri = URI.create("https://fill.papermc.io/v3/projects/paper/versions/" + mcVersion + "/builds/" + build);
        String meta = http.send(HttpRequest.newBuilder(metaUri).header("User-Agent", AGENT).build(), BodyHandlers.ofString()).body();
        String url = JsonParser.parseString(meta).getAsJsonObject().getAsJsonObject("downloads")
                .getAsJsonObject("server:default").get("url").getAsString();
        Path part = cacheDir.resolve(jar.getFileName() + ".part");
        http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", AGENT).build(), BodyHandlers.ofFile(part));
        String got = sha256(part);
        if (!got.equals(sha256)) throw new IllegalStateException("Paper jar checksum mismatch: " + got);
        Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
}
