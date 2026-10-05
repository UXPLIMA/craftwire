package com.uxplima.craftwire.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Connection details the hub writes to {@code <home>/hub.json}. Re-read on every connect attempt. */
public record HubConfig(int port, String token) {
    public static Path defaultHome() {
        String env = System.getenv("CRAFTWIRE_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".craftwire");
    }

    public static Optional<HubConfig> load(Path home) {
        Path file = home.resolve("hub.json");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!o.has("port") || !o.has("token")) return Optional.empty();
            return Optional.of(new HubConfig(o.get("port").getAsInt(), o.get("token").getAsString()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
