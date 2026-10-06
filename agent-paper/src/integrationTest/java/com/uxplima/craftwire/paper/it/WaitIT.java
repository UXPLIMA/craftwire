package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** wait: server-side conditions checked every tick, and events fired after the call. */
class WaitIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    @BeforeEach
    void spawn() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"Wtr\"],\"location\":{\"x\":20.5,\"y\":-60,\"z\":20.5}}");
        Thread.sleep(300);
    }

    @AfterEach
    void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
    }

    void command(String command) throws Exception {
        hub.result("server.command", "{\"command\":\"" + command + "\"}");
    }

    CompletableFuture<JsonObject> waitFor(String json) throws Exception {
        CompletableFuture<JsonObject> f = hub.send("wait", json);
        Thread.sleep(300);   // the wait is armed and has seen the current state
        assertFalse(f.isDone(), "the condition does not hold yet: " + (f.isDone() ? f.get() : ""));
        return f;
    }

    static JsonObject result(CompletableFuture<JsonObject> f) throws Exception {
        JsonObject r = f.get(20, TimeUnit.SECONDS);
        if (r.has("error")) throw new AssertionError("wait failed: " + r.get("error"));
        return r.getAsJsonObject("result");
    }

    @Test
    void aBlockConditionHoldsOnceTheBlockMatchesIncludingOnlyTheGivenProperties() throws Exception {
        command("minecraft:setblock 22 -60 22 minecraft:oak_fence_gate[open=false]");
        CompletableFuture<JsonObject> f = waitFor("{\"condition\":\"block\",\"x\":22,\"y\":-60,\"z\":22,\"is\":\"oak_fence_gate[open=true]\",\"timeoutMs\":10000}");
        command("minecraft:setblock 22 -60 22 minecraft:oak_fence_gate[open=true,facing=east]");
        JsonObject r = result(f);
        assertTrue(r.get("matched").getAsBoolean(), r.toString());
        assertEquals("block", r.get("condition").getAsString());
        assertTrue(r.get("value").getAsString().startsWith("minecraft:oak_fence_gate["), r.toString());
        assertTrue(r.get("elapsedMs").getAsLong() >= 250, r.toString());

        CompletableFuture<JsonObject> gone = waitFor("{\"condition\":\"block\",\"x\":22,\"y\":-60,\"z\":22,\"isNot\":\"oak_fence_gate\",\"timeoutMs\":10000}");
        command("minecraft:setblock 22 -60 22 minecraft:air");
        assertEquals("minecraft:air", result(gone).get("value").getAsString());
    }

    @Test
    void aTimeoutReportsWhatWasLastSeen() throws Exception {
        command("minecraft:setblock 23 -60 23 minecraft:air");
        JsonObject r = hub.result("wait", "{\"condition\":\"block\",\"x\":23,\"y\":-60,\"z\":23,\"is\":\"diamond_block\",\"timeoutMs\":300}").getAsJsonObject();
        assertFalse(r.get("matched").getAsBoolean());
        assertEquals("minecraft:air", r.get("last").getAsString());
        assertTrue(r.get("elapsedMs").getAsLong() >= 300, r.toString());
    }

    @Test
    void waitsForAPlayerToArriveAndForItemsToAddUp() throws Exception {
        CompletableFuture<JsonObject> near = waitFor("{\"condition\":\"player_near\",\"player\":\"Wtr\",\"x\":30,\"y\":-60,\"z\":30,\"radius\":2,\"timeoutMs\":10000}");
        command("minecraft:tp Wtr 30.5 -60 29.5");
        JsonObject at = result(near).getAsJsonObject("value");
        assertTrue(at.get("distance").getAsDouble() <= 2, at.toString());

        CompletableFuture<JsonObject> items = waitFor("{\"condition\":\"inventory\",\"player\":\"Wtr\",\"item\":\"diamond\",\"count\":3,\"timeoutMs\":10000}");
        command("minecraft:give Wtr minecraft:diamond 2");
        Thread.sleep(200);
        assertFalse(items.isDone(), "2 of 3 diamonds");
        command("minecraft:give Wtr minecraft:diamond 1");
        assertEquals(3, result(items).get("value").getAsInt());

        CompletableFuture<JsonObject> none = waitFor("{\"condition\":\"inventory\",\"player\":\"Wtr\",\"item\":\"minecraft:diamond\",\"atMost\":0,\"timeoutMs\":10000}");
        command("minecraft:clear Wtr minecraft:diamond");
        assertEquals(0, result(none).get("value").getAsInt());
    }

    @Test
    void waitsForAnEventAboutAPlayerWithMatchingValues() throws Exception {
        CompletableFuture<JsonObject> f = waitFor("{\"condition\":\"event\",\"type\":\"FixtureShopEvent\",\"player\":\"Wtr\",\"pattern\":\"\\\"cancelled\\\":true\",\"timeoutMs\":10000}");
        hub.result("bot.action", "{\"bot\":\"Wtr\",\"action\":\"command\",\"command\":\"/cwfixture buy\",\"collectMs\":0}");
        JsonObject e = result(f).getAsJsonObject("value");
        assertEquals("FixtureShopEvent", e.get("type").getAsString());
        assertEquals(30, e.getAsJsonObject("fields").get("price").getAsInt());
    }

    @Test
    void aBusyEventIsTappedOnlyWhileWaitedFor() throws Exception {
        assertFalse(hub.result("events", "{\"action\":\"listeners\",\"type\":\"BlockPhysicsEvent\"}").getAsJsonObject().get("recorded").getAsBoolean());
        CompletableFuture<JsonObject> f = waitFor("{\"condition\":\"event\",\"type\":\"BlockPhysicsEvent\",\"timeoutMs\":10000}");
        assertTrue(hub.result("events", "{\"action\":\"listeners\",\"type\":\"BlockPhysicsEvent\"}").getAsJsonObject().get("recorded").getAsBoolean());
        command("minecraft:setblock 24 -60 24 minecraft:stone");
        assertTrue(result(f).get("matched").getAsBoolean());
        Thread.sleep(100);
        assertFalse(hub.result("events", "{\"action\":\"listeners\",\"type\":\"BlockPhysicsEvent\"}").getAsJsonObject().get("recorded").getAsBoolean());
        command("minecraft:setblock 24 -60 24 minecraft:air");
    }

    @Test
    void waitsForABotToReceiveAMessage() throws Exception {
        CompletableFuture<JsonObject> f = waitFor("{\"condition\":\"message\",\"player\":\"Wtr\",\"pattern\":\"fixture: blocked\",\"timeoutMs\":10000}");
        hub.result("bot.action", "{\"bot\":\"Wtr\",\"action\":\"command\",\"command\":\"/cwfixture blocked\",\"collectMs\":0}");
        assertEquals("fixture: blocked", result(f).getAsJsonObject("value").get("text").getAsString());
    }

    @Test
    void waitsForAScriptToBecomeTruthy() throws Exception {
        hub.result("server.eval", "{\"code\":\"globalThis.ready = 0\"}");
        CompletableFuture<JsonObject> f = waitFor("{\"condition\":\"expr\",\"js\":\"globalThis.ready\",\"timeoutMs\":10000}");
        hub.result("server.eval", "{\"code\":\"globalThis.ready = 'yes'\"}");
        assertEquals("yes", result(f).get("value").getAsString());

        JsonObject broken = hub.error("wait", "{\"condition\":\"expr\",\"js\":\"nope(\",\"timeoutMs\":1000}");
        assertEquals("EVAL_ERROR", broken.get("code").getAsString());
    }

    @Test
    void rejectsConditionsItCannotCheck() throws Exception {
        assertEquals("INVALID_PARAMS", hub.error("wait", "{\"condition\":\"block\",\"x\":0,\"y\":0,\"z\":0}").get("code").getAsString());
        assertEquals("INVALID_PARAMS", hub.error("wait", "{\"condition\":\"block\",\"x\":0,\"y\":0,\"z\":0,\"is\":\"not_a_block\"}").get("code").getAsString());
        assertEquals("INVALID_PARAMS", hub.error("wait", "{\"condition\":\"inventory\",\"player\":\"Wtr\",\"item\":\"nope\"}").get("code").getAsString());
        assertEquals("BOT_NOT_FOUND", hub.error("wait", "{\"condition\":\"message\",\"player\":\"Ghost\",\"pattern\":\"x\"}").get("code").getAsString());
        assertEquals("EVENT_NOT_FOUND", hub.error("wait", "{\"condition\":\"event\",\"type\":\"NoSuchEvent\"}").get("code").getAsString());
        assertEquals("INVALID_PARAMS", hub.error("wait", "{\"condition\":\"sunrise\"}").get("code").getAsString());
    }
}
