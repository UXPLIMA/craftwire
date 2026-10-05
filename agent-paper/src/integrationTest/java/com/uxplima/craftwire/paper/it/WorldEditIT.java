package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WorldEditIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    static JsonObject edit(String paramsJson) throws Exception {
        return hub.result("world.edit", paramsJson).getAsJsonObject();
    }

    static String blockAt(int x, int y, int z) throws Exception {
        return hub.result("world.query", "{\"action\":\"block\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}")
                .getAsJsonObject().get("block").getAsString();
    }

    static String box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return "\"min\":{\"x\":" + x1 + ",\"y\":" + y1 + ",\"z\":" + z1 + "},\"max\":{\"x\":" + x2 + ",\"y\":" + y2 + ",\"z\":" + z2 + "}";
    }

    @Test
    void setBlocksAcceptsStates() throws Exception {
        JsonObject r = edit("{\"action\":\"set_blocks\",\"blocks\":["
                + "{\"x\":3000,\"y\":-50,\"z\":3000,\"block\":\"minecraft:oak_stairs[facing=east]\"},"
                + "{\"x\":3001,\"y\":-50,\"z\":3000,\"block\":\"gold_block\"}]}");
        assertEquals(2, r.get("changed").getAsInt());
        assertTrue(blockAt(3000, -50, 3000).contains("facing=east"));
        assertEquals("minecraft:gold_block", blockAt(3001, -50, 3000));
    }

    @Test
    void fillWithReplaceOnlyChangesMatchingBlocks() throws Exception {
        edit("{\"action\":\"fill\"," + box(3010, -50, 3010, 3012, -50, 3012) + ",\"block\":\"stone\"}");
        edit("{\"action\":\"set_blocks\",\"blocks\":[{\"x\":3011,\"y\":-50,\"z\":3011,\"block\":\"gold_block\"}]}");
        JsonObject r = edit("{\"action\":\"fill\"," + box(3010, -50, 3010, 3012, -50, 3012) + ",\"block\":\"dirt\",\"replace\":\"stone\"}");
        assertEquals(8, r.get("changed").getAsInt());
        assertEquals("minecraft:gold_block", blockAt(3011, -50, 3011));
        assertEquals("minecraft:dirt", blockAt(3010, -50, 3010));
    }

    @Test
    void oversizedEditIsRejected() throws Exception {
        JsonObject e = hub.error("world.edit", "{\"action\":\"fill\"," + box(0, -60, 0, 1000, -50, 1000) + ",\"block\":\"stone\"}");
        assertEquals("EDIT_TOO_LARGE", e.get("code").getAsString());
    }

    @Test
    void largeFillTakesASnapshotThatRestores() throws Exception {
        JsonObject r = edit("{\"action\":\"fill\"," + box(3100, -40, 3100, 3139, -20, 3139) + ",\"block\":\"stone\"}");   // 33,600 blocks
        assertEquals(33_600, r.get("volume").getAsInt());
        String id = r.get("snapshotId").getAsString();
        assertEquals("minecraft:stone", blockAt(3120, -30, 3120));
        edit("{\"action\":\"restore\",\"id\":\"" + id + "\"}");
        assertEquals("minecraft:air", blockAt(3120, -30, 3120));
    }

    @Test
    void smallEditsTakeNoSnapshot() throws Exception {
        assertFalse(edit("{\"action\":\"fill\"," + box(3150, -50, 3150, 3151, -50, 3151) + ",\"block\":\"stone\"}").has("snapshotId"));
    }

    @Test
    void explicitSnapshotRoundTrip() throws Exception {
        String id = edit("{\"action\":\"snapshot\"," + box(3200, -50, 3200, 3202, -50, 3202) + "}").get("id").getAsString();
        edit("{\"action\":\"set_blocks\",\"blocks\":[{\"x\":3201,\"y\":-50,\"z\":3201,\"block\":\"gold_block\"}]}");
        edit("{\"action\":\"restore\",\"id\":\"" + id + "\"}");
        assertEquals("minecraft:air", blockAt(3201, -50, 3201));
    }

    @Test
    void unknownSnapshotIsNotFound() throws Exception {
        assertEquals("SNAPSHOT_NOT_FOUND", hub.error("world.edit", "{\"action\":\"restore\",\"id\":\"snap-1-1\"}").get("code").getAsString());
    }

    @Test
    void savedSchematicPastesWithRotation() throws Exception {
        edit("{\"action\":\"set_blocks\",\"blocks\":["
                + "{\"x\":3300,\"y\":-50,\"z\":3300,\"block\":\"gold_block\"},"
                + "{\"x\":3301,\"y\":-50,\"z\":3300,\"block\":\"iron_block\"}]}");
        edit("{\"action\":\"save_schematic\",\"name\":\"probe\"," + box(3300, -50, 3300, 3301, -50, 3300) + "}");
        JsonObject r = edit("{\"action\":\"paste_schematic\",\"name\":\"probe\",\"at\":{\"x\":3400,\"y\":-50,\"z\":3400},\"rotation\":\"clockwise_90\"}");
        assertEquals("{\"x\":3400,\"y\":-50,\"z\":3401}", r.get("max").toString());
        assertEquals("minecraft:gold_block", blockAt(3400, -50, 3400));
        assertEquals("minecraft:iron_block", blockAt(3400, -50, 3401));
    }

    @Test
    void unknownSchematicIsNotFound() throws Exception {
        assertEquals("SCHEMATIC_NOT_FOUND", hub.error("world.edit",
                "{\"action\":\"paste_schematic\",\"name\":\"never_saved\",\"at\":{\"x\":0,\"y\":0,\"z\":0}}").get("code").getAsString());
    }

    @Test
    void invalidBlockIsInvalidParams() throws Exception {
        assertEquals("INVALID_PARAMS", hub.error("world.edit",
                "{\"action\":\"fill\"," + box(0, 0, 0, 0, 0, 0) + ",\"block\":\"not_a_block\"}").get("code").getAsString());
    }
}
