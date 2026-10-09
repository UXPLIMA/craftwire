package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** profile and trace against the fixture plugin burning 8 ms of every tick. */
class ProfileIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    private static void lag(int ticks) throws Exception {
        hub.result("server.command", "{\"command\":\"cwfixture lag " + ticks + "\"}");
    }

    private static JsonObject find(JsonArray a, String key, String value) {
        for (JsonElement e : a) if (e.getAsJsonObject().get(key).getAsString().equals(value)) return e.getAsJsonObject();
        throw new AssertionError(value + " not in " + a);
    }

    @Test
    void profileNamesThePluginTheTaskAndTheTickTimes() throws Exception {
        lag(200);
        JsonObject r = hub.call("profile.run", "{\"durationMs\":3000}", 30_000).getAsJsonObject("result");
        assertNotNull(r, "profile failed");
        assertEquals(ItEnv.folia() ? "Folia Region Scheduler Thread #*" : "Server thread", r.get("thread").getAsString());
        assertTrue(r.get("samples").getAsInt() > 20, r.toString());
        // Folia: every region thread is sampled and listed; Paper has the one main thread.
        assertEquals(ItEnv.folia(), r.has("threads"), r.toString());

        JsonObject fixture = find(r.getAsJsonArray("owners"), "owner", "CraftwireFixture");
        assertEquals("plugin", fixture.get("kind").getAsString());
        // 8 ms of every 50 ms tick, while the rest of the tick mostly sleeps: it is the biggest busy owner.
        assertTrue(fixture.get("percent").getAsDouble() > 5, r.toString());

        JsonObject entry = find(r.getAsJsonArray("entryPoints"), "owner", "CraftwireFixture");
        assertTrue(entry.get("method").getAsString().startsWith("com.uxplima.craftwire.fixtures.FixtureLag"), entry.toString());
        assertTrue(entry.has("task"), entry.toString());

        JsonObject hot = find(r.getAsJsonArray("hotMethods"), "method", "com.uxplima.craftwire.fixtures.FixtureLag.burn");
        assertEquals("CraftwireFixture", hot.get("owner").getAsString());

        JsonObject ticks = r.getAsJsonObject("ticks");
        assertTrue(ticks.get("count").getAsInt() >= 40, ticks.toString());
        // Folia reports every region's ticks, most of them idle: only Paper's average reflects the 8 ms burn.
        if (!ItEnv.folia()) assertTrue(ticks.get("msptAvg").getAsDouble() >= 7, ticks.toString());
        assertTrue(ticks.get("max").getAsDouble() >= 7, ticks.toString());
        JsonArray slowest = ticks.getAsJsonArray("slowest");
        assertFalse(slowest.isEmpty());
    }

    @Test
    void aListenerEntryPointNamesItsEvent() throws Exception {
        hub.result("server.command", "{\"command\":\"cwfixture lag 200 event\"}");
        JsonObject r = hub.call("profile.run", "{\"durationMs\":3000}", 30_000).getAsJsonObject("result");
        JsonObject entry = find(r.getAsJsonArray("entryPoints"), "method", "com.uxplima.craftwire.fixtures.FixturePlugin.onTick");
        assertEquals("ServerTickStartEvent", entry.get("event").getAsString(), entry.toString());
        assertEquals("CraftwireFixture", entry.get("owner").getAsString());
    }

    @Test
    void traceCountsCallsOfTheMethodAndShowsItsCaller() throws Exception {
        lag(400);
        CompletableFuture<JsonObject> f = hub.send("trace.run", "{\"method\":\"com.uxplima.craftwire.fixtures.FixtureLag::burn\",\"durationMs\":2000}");
        JsonObject r = f.get(30, TimeUnit.SECONDS).getAsJsonObject("result");
        assertNotNull(r, "trace failed");
        JsonObject m = r.getAsJsonArray("methods").get(0).getAsJsonObject();
        assertEquals("com.uxplima.craftwire.fixtures.FixtureLag.burn", m.get("method").getAsString());
        assertEquals("CraftwireFixture", m.get("owner").getAsString());
        assertTrue(m.get("invocations").getAsLong() >= 20, r.toString());
        assertTrue(m.get("avgMs").getAsDouble() >= 7, r.toString());
        JsonObject call = r.getAsJsonArray("slowest").get(0).getAsJsonObject();
        if (ItEnv.folia()) assertTrue(call.get("thread").getAsString().startsWith("Folia Region Scheduler Thread"), call.toString());
        else assertEquals("Server thread", call.get("thread").getAsString());
        assertTrue(call.getAsJsonArray("stack").get(0).getAsJsonObject().get("method").getAsString()
                .startsWith("com.uxplima.craftwire.fixtures.FixtureLag"), call.toString());
    }

    @Test
    void aBadFilterIsRefused() throws Exception {
        JsonObject e = hub.error("trace.run", "{\"method\":\"not a method\"}");
        assertEquals("INVALID_PARAMS", e.get("code").getAsString());
    }
}
