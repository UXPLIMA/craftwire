package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HubConfigTest {
    @Test
    void loadsPortAndToken(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("hub.json"), "{\"port\": 47821, \"token\": \"" + "a".repeat(64) + "\"}");
        HubConfig cfg = HubConfig.load(home).orElseThrow();
        assertEquals(47821, cfg.port());
        assertEquals("a".repeat(64), cfg.token());
    }

    @Test
    void emptyWhenMissing(@TempDir Path home) {
        assertTrue(HubConfig.load(home).isEmpty());
    }

    @Test
    void emptyWhenCorrupt(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("hub.json"), "{nope");
        assertTrue(HubConfig.load(home).isEmpty());
    }

    @Test
    void emptyWhenFieldsMissing(@TempDir Path home) throws Exception {
        Files.writeString(home.resolve("hub.json"), "{\"port\": 1}");
        assertTrue(HubConfig.load(home).isEmpty());
    }
}
