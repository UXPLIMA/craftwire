package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConnectionIT {
    @Test
    void helloIdentifiesThePaperAgent() throws Exception {
        JsonObject h = ItEnv.get().hello;
        assertEquals("server", h.get("agentKind").getAsString());
        assertEquals(1, h.get("protocolVersion").getAsInt());
        assertEquals(System.getProperty("craftwire.mcVersion"), h.get("mcVersion").getAsString());
        assertEquals("server", h.get("instanceName").getAsString());
        assertEquals(System.getProperty("craftwire.version"), h.get("agentVersion").getAsString());
        Path expected = Path.of(System.getProperty("craftwire.itDir"), "server");
        assertEquals(expected.toRealPath(), Path.of(h.get("serverDir").getAsString()).toRealPath());
        long pid = h.get("pid").getAsLong();
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "pid " + pid + " is not alive");
    }

    @Test
    void startupLinesAreReplayedIncludingTheProductionWarning() throws Exception {
        ItHub hub = ItEnv.get().hub;
        hub.awaitLog(m -> m.contains("Craftwire is active — do not run on production servers"), 10_000);
        // Logged after onLoad but before the plugin enabled and connected: only the backlog can deliver it.
        hub.awaitLog(m -> m.startsWith("Preparing level"), 10_000);
    }

    @Test
    void serverInfoReportsVersionsAndPlugins() throws Exception {
        JsonObject info = ItEnv.get().hub.result("server.info", "{}").getAsJsonObject();
        assertEquals(System.getProperty("craftwire.mcVersion"), info.get("minecraftVersion").getAsString());
        assertEquals(3, info.getAsJsonArray("tps").size());
        assertTrue(info.getAsJsonArray("plugins").toString().contains("\"name\":\"Craftwire\""), info.toString());
        assertEquals("world", info.getAsJsonArray("worlds").get(0).getAsJsonObject().get("name").getAsString());
        // Folia adds the regions' own tick rates; Paper has one main thread and no such fields.
        assertEquals(ItEnv.folia(), info.has("folia"), info.toString());
        if (ItEnv.folia()) assertTrue(info.getAsJsonArray("regions").toString().contains("\"at\":\"spawn\""), info.toString());
    }

    @Test
    void unknownMethodsAreStructuredErrors() throws Exception {
        assertEquals("UNKNOWN_METHOD", ItEnv.get().hub.error("no.such.method", "{}").get("code").getAsString());
    }

    @Test
    void reconnectReplaysTheBacklogToTheNewSession() throws Exception {
        ItHub hub = ItEnv.get().hub;
        hub.dropConnectionAndClearEvents();
        hub.awaitHello(30_000, () -> true);
        hub.awaitLog(m -> m.startsWith("Done ("), 10_000);
        // The drop itself is logged where an admin can see it, not only at debug level.
        hub.awaitLog(m -> m.startsWith("Lost the connection to the Craftwire hub"), 10_000);
    }
}
