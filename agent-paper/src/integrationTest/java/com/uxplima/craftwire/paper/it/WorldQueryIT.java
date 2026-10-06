package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldQueryIT {
    static ItHub hub;

    /** Far from spawn (chunks start unloaded); gold at 1007 and 1008 sits in two different chunks.
     *  Generating those chunks synchronously can take seconds, hence the long eval timeout. */
    @BeforeAll
    static void build() throws Exception {
        hub = ItEnv.get().hub;
        hub.result("server.eval", "{\"code\":"
                + "\"const W = server.getWorlds().get(0), M = Java.type('org.bukkit.Material');"
                + " for (let x = 1000; x <= 1002; x++) for (let z = 1000; z <= 1002; z++) W.getBlockAt(x, -50, z).setType(M.GOLD_BLOCK);"
                + " W.getBlockAt(1007, -50, 1007).setType(M.GOLD_BLOCK);"
                + " W.getBlockAt(1008, -50, 1008).setType(M.GOLD_BLOCK);"
                + " W.spawnEntity(loc(1001.5, -49, 1001.5), Java.type('org.bukkit.entity.EntityType').ARMOR_STAND); 'ok'\",\"timeoutMs\":60000}");
    }

    static JsonObject query(String paramsJson) throws Exception {
        return hub.result("world.query", paramsJson).getAsJsonObject();
    }

    @Test
    void blockReportsItsState() throws Exception {
        assertEquals("minecraft:gold_block", query("{\"action\":\"block\",\"x\":1000,\"y\":-50,\"z\":1000}").get("block").getAsString());
    }

    @Test
    void regionRunLengthEncodesByDefault() throws Exception {
        JsonObject r = query("{\"action\":\"region\",\"min\":{\"x\":1000,\"y\":-50,\"z\":1000},\"max\":{\"x\":1002,\"y\":-49,\"z\":1002}}");
        assertFalse(r.has("blocks"));
        // y=-50 is all gold (9 blocks), y=-49 all air (9): two runs of [palette index, count].
        assertEquals("[[0,9],[1,9]]", r.get("runs").toString());
        assertEquals("[\"minecraft:gold_block\",\"minecraft:air\"]", r.get("palette").toString());
    }

    @Test
    void regionReturnsPaletteIndicesAndCounts() throws Exception {
        JsonObject r = query("{\"action\":\"region\",\"encoding\":\"indices\",\"min\":{\"x\":1000,\"y\":-50,\"z\":1000},\"max\":{\"x\":1002,\"y\":-49,\"z\":1002}}");
        assertEquals("{\"x\":3,\"y\":2,\"z\":3}", r.get("size").toString());
        assertEquals(18, r.getAsJsonArray("blocks").size());
        assertEquals(9, r.getAsJsonObject("counts").get("minecraft:gold_block").getAsInt());
        assertEquals(9, r.getAsJsonObject("counts").get("minecraft:air").getAsInt());
        int first = r.getAsJsonArray("blocks").get(0).getAsInt();
        assertEquals("minecraft:gold_block", r.getAsJsonArray("palette").get(first).getAsString());
    }

    @Test
    void regionOverTheLimitIsRejected() throws Exception {
        JsonObject e = hub.error("world.query", "{\"action\":\"region\",\"min\":{\"x\":0,\"y\":0,\"z\":0},\"max\":{\"x\":100,\"y\":100,\"z\":100}}");
        assertEquals("QUERY_TOO_LARGE", e.get("code").getAsString());
    }

    @Test
    void findBlockSearchesAcrossChunkBorders() throws Exception {
        JsonObject r = query("{\"action\":\"find_block\",\"block\":\"gold_block\",\"min\":{\"x\":995,\"y\":-55,\"z\":995},\"max\":{\"x\":1010,\"y\":-45,\"z\":1010},\"limit\":100}");
        JsonArray m = r.getAsJsonArray("matches");
        assertEquals(11, m.size());
        assertFalse(r.get("truncated").getAsBoolean());
        assertTrue(m.toString().contains("\"x\":1008,\"y\":-50,\"z\":1008"), m.toString());
    }

    @Test
    void findBlockHonoursTheLimit() throws Exception {
        JsonObject r = query("{\"action\":\"find_block\",\"block\":\"minecraft:gold_block\",\"min\":{\"x\":995,\"y\":-55,\"z\":995},\"max\":{\"x\":1010,\"y\":-45,\"z\":1010},\"limit\":2}");
        assertEquals(2, r.getAsJsonArray("matches").size());
        assertTrue(r.get("truncated").getAsBoolean());
    }

    @Test
    void entitiesFilterByType() throws Exception {
        JsonObject r = query("{\"action\":\"entities\",\"type\":\"armor_stand\",\"min\":{\"x\":995,\"y\":-60,\"z\":995},\"max\":{\"x\":1010,\"y\":-40,\"z\":1010}}");
        JsonArray list = r.getAsJsonArray("entities");
        assertEquals(1, list.size());
        assertEquals("minecraft:armor_stand", list.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void playersIsEmptyWithNobodyOnline() throws Exception {
        assertEquals(0, query("{\"action\":\"players\"}").getAsJsonArray("players").size());
    }

    @Test
    void unknownWorldIsWorldNotFound() throws Exception {
        assertEquals("WORLD_NOT_FOUND", hub.error("world.query", "{\"action\":\"block\",\"world\":\"nope\",\"x\":0,\"y\":0,\"z\":0}").get("code").getAsString());
    }

    @Test
    void unknownBlockIsInvalidParams() throws Exception {
        assertEquals("INVALID_PARAMS", hub.error("world.query",
                "{\"action\":\"find_block\",\"block\":\"not_a_block\",\"min\":{\"x\":0,\"y\":0,\"z\":0},\"max\":{\"x\":1,\"y\":1,\"z\":1}}").get("code").getAsString());
    }
}
