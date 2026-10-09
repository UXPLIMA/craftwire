package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What only Folia has: regions ticking on their own threads. Runs with -Pserver=folia; skipped on Paper. */
class FoliaIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        Assumptions.assumeTrue(ItEnv.folia(), "Folia only");
        hub = ItEnv.get().hub;
    }

    @AfterEach
    void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
    }

    static String blockAt(int x, int y, int z) throws Exception {
        return hub.result("world.query", "{\"action\":\"block\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}")
                .getAsJsonObject().get("block").getAsString();
    }

    @Test
    void botsInFarApartRegionsActAtTheSameTime() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"Near\"],\"location\":{\"x\":0.5,\"y\":-60,\"z\":40.5}}");
        hub.result("bot.spawn", "{\"names\":[\"Far\"],\"location\":{\"x\":6000.5,\"y\":-60,\"z\":6000.5}}");
        CompletableFuture<JsonObject> near = hub.send("bot.action", "{\"bot\":\"Near\",\"action\":\"move_to\",\"x\":8.5,\"y\":-60,\"z\":40.5,\"path\":false}");
        CompletableFuture<JsonObject> far = hub.send("bot.action", "{\"bot\":\"Far\",\"action\":\"move_to\",\"x\":6008.5,\"y\":-60,\"z\":6000.5,\"path\":false}");
        assertTrue(near.get(30, TimeUnit.SECONDS).getAsJsonObject("result").get("reached").getAsBoolean(), near.get().toString());
        assertTrue(far.get(30, TimeUnit.SECONDS).getAsJsonObject("result").get("reached").getAsBoolean(), far.get().toString());

        // Two regions, each with its own tick rate, and a player in each.
        JsonObject info = hub.result("server.info", "{}").getAsJsonObject();
        assertTrue(info.get("folia").getAsBoolean());
        int players = 0;
        for (JsonElement e : info.getAsJsonArray("regions")) {
            JsonObject r = e.getAsJsonObject();
            assertEquals(5, r.getAsJsonArray("tps").size(), r.toString());
            if (r.get("at").getAsString().equals("player")) players++;
        }
        assertEquals(2, players, info.toString());
        assertTrue(info.has("slowestRegion"), info.toString());
    }

    @Test
    void aBoxSpanningRegionsIsEditedAndRestored() throws Exception {
        // 1200 blocks long: several Folia region sections, each part run on the thread that owns it.
        JsonObject r = hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":-600,\"y\":-55,\"z\":7000},"
                + "\"max\":{\"x\":599,\"y\":-46,\"z\":7002},\"block\":\"stone\"}").getAsJsonObject();
        assertEquals(36_000, r.get("volume").getAsInt());
        String id = r.get("snapshotId").getAsString();
        assertEquals("minecraft:stone", blockAt(-600, -50, 7001));
        assertEquals("minecraft:stone", blockAt(599, -50, 7001));
        hub.result("world.edit", "{\"action\":\"restore\",\"id\":\"" + id + "\"}");
        assertEquals("minecraft:air", blockAt(-600, -50, 7001));
        assertEquals("minecraft:air", blockAt(599, -50, 7001));
    }

    @Test
    void aWrongThreadBlockChangeIsLoggedWithThePluginsFrame() throws Exception {
        hub.result("server.command", "{\"command\":\"cwfixture wrongthread\"}");
        JsonObject log = hub.awaitEvent(e -> "log".equals(e.get("type").getAsString())
                && e.getAsJsonObject("data").has("thrown")
                && e.getAsJsonObject("data").get("thrown").getAsString().contains("Thread failed main thread check"), 10_000);
        assertTrue(log.getAsJsonObject("data").get("thrown").getAsString().contains("com.uxplima.craftwire.fixtures.FixturePlugin"), log.toString());
    }

    @Test
    void evalExplainsAWrongThreadAndRunsWhereItIsTold() throws Exception {
        String code = "server.getWorlds().get(0).getBlockAt(6500, -61, 6500).getType().name()";
        JsonObject wrong = hub.error("server.eval", "{\"code\":\"" + code + "\"}");
        assertEquals("WRONG_THREAD", wrong.get("code").getAsString(), wrong.toString());
        assertTrue(wrong.get("hint").getAsString().contains("at {world,x,z}"), wrong.toString());
        JsonObject ok = hub.result("server.eval", "{\"code\":\"" + code + "\",\"at\":{\"x\":6500,\"z\":6500}}").getAsJsonObject();
        assertEquals("GRASS_BLOCK", ok.get("result").getAsString(), ok.toString());
    }

    @Test
    void asPlayerRunsOnThePlayersThread() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"Owner\"],\"location\":{\"x\":5500.5,\"y\":-60,\"z\":5500.5}}");
        JsonObject r = hub.result("server.eval", "{\"code\":\"player('Owner').getLocation().getBlock().getType().name()\",\"asPlayer\":\"Owner\"}").getAsJsonObject();
        assertEquals("AIR", r.get("result").getAsString(), r.toString());
        JsonObject cmd = hub.result("server.command", "{\"command\":\"cwfixture\",\"asPlayer\":\"Owner\"}").getAsJsonObject();
        assertTrue(cmd.get("success").getAsBoolean(), cmd.toString());
    }
}
