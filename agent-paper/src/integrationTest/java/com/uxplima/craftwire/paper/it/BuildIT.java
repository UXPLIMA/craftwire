package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    static JsonObject build(String project, int x, int z, String code) throws Exception {
        return build("{\"project\":\"" + project + "\",\"origin\":{\"x\":" + x + ",\"y\":-50,\"z\":" + z + "},\"code\":" + quote(code) + "}");
    }

    static JsonObject build(String json) throws Exception {
        return hub.result("build.run", json).getAsJsonObject();
    }

    static JsonObject manage(String json) throws Exception {
        return hub.result("build.manage", json).getAsJsonObject();
    }

    static String quote(String s) {
        return new com.google.gson.Gson().toJson(s);
    }

    static String blockAt(int x, int y, int z) throws Exception {
        return hub.result("world.query", "{\"action\":\"block\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}").getAsJsonObject().get("block").getAsString();
    }

    static String region(int x1, int y1, int z1, int x2, int y2, int z2) throws Exception {
        return hub.result("world.query", "{\"action\":\"region\",\"min\":{\"x\":" + x1 + ",\"y\":" + y1 + ",\"z\":" + z1 + "},\"max\":{\"x\":" + x2 + ",\"y\":" + y2 + ",\"z\":" + z2 + "}}").toString();
    }

    @Test
    void buildsAndReportsCounts() throws Exception {
        JsonObject r = build("b1", 4000, 4000, "fill(0,0,0,4,4,4,'stone_bricks')");
        assertEquals(125, r.get("changed").getAsInt());
        assertEquals(125, r.get("blocks").getAsInt());
        assertEquals("minecraft:stone_bricks", blockAt(4002, -48, 4002));
    }

    @Test
    void rerunWritesNothing() throws Exception {
        build("b2", 4020, 4000, "fill(0,0,0,4,0,4,'stone')");
        assertEquals(0, build("b2", 4020, 4000, "fill(0,0,0,4,0,4,'stone')").get("changed").getAsInt());
    }

    @Test
    void editAndRerunWritesOnlyTheChange() throws Exception {
        build("b3", 4040, 4000, "fill(0,0,0,4,0,4,'stone')");
        JsonObject r = build("b3", 4040, 4000, "fill(0,0,0,4,0,4,'stone'); set(2,0,2,'gold_block')");
        assertEquals(1, r.get("changed").getAsInt());
    }

    @Test
    void clearAndUndoRestoreExactly() throws Exception {
        String before = region(4060, -51, 4000, 4066, -44, 4006);
        build("b4", 4060, 4000, "hollow(0,-1,0,6,6,6,'oak_planks'); roof({x1:0,z1:0,x2:6,z2:6,y:7,type:'gable',stairs:'oak_stairs'})");
        build("b4", 4060, 4000, "fill(0,-1,0,6,0,6,'gold_block')");
        manage("{\"action\":\"undo\",\"project\":\"b4\"}");
        assertEquals("minecraft:oak_planks", blockAt(4060, -51, 4000));
        manage("{\"action\":\"clear\",\"project\":\"b4\"}");
        assertEquals(before, region(4060, -51, 4000, 4066, -44, 4006));
    }

    @Test
    void removingUpperPartRevealsLowerPart() throws Exception {
        String code = "part('lower', () => fill(0,0,0,2,0,0,'stone'));\npart('upper', () => set(1,0,0,'gold_block'));";
        build("{\"project\":\"b5\",\"origin\":{\"x\":4080,\"y\":-50,\"z\":4000},\"code\":" + quote(code) + "}");
        assertEquals("minecraft:gold_block", blockAt(4081, -50, 4000));
        manage("{\"action\":\"remove\",\"project\":\"b5\",\"part\":\"upper\"}");
        assertEquals("minecraft:stone", blockAt(4081, -50, 4000));
    }

    @Test
    void moveAndRotateCarryTheBuild() throws Exception {
        build("b6", 4100, 4000, "set(0,0,0,'oak_stairs[facing=north]')");
        manage("{\"action\":\"move\",\"project\":\"b6\",\"origin\":{\"x\":4110,\"y\":-50,\"z\":4000},\"rotation\":90}");
        assertEquals("minecraft:air", blockAt(4100, -50, 4000));
        assertTrue(blockAt(4110, -50, 4000).contains("facing=east"));
    }

    @Test
    void dryRunWritesNothing() throws Exception {
        JsonObject r = build("{\"project\":\"b7\",\"origin\":{\"x\":4120,\"y\":-50,\"z\":4000},\"code\":\"fill(0,0,0,2,2,2,'stone')\",\"dryRun\":true}");
        assertEquals(27, r.get("blocks").getAsInt());
        assertEquals("minecraft:air", blockAt(4121, -49, 4001));
    }

    @Test
    void badBlockWritesNothing() throws Exception {
        JsonObject e = hub.error("build.run", "{\"project\":\"b8\",\"origin\":{\"x\":4130,\"y\":-50,\"z\":4000},\"code\":\"set(0,0,0,'stone'); set(1,0,0,'stone_brick')\"}");
        assertEquals("BAD_BLOCK", e.get("code").getAsString());
        assertEquals("minecraft:air", blockAt(4130, -50, 4000));
    }

    @Test
    void buildsIntoUnloadedChunks() throws Exception {
        JsonObject r = build("b9", 20_000, 20_000, "fill(0,0,0,40,0,40,'stone')");
        assertEquals(41 * 41, r.get("changed").getAsInt());
    }

    @Test
    void projectSurvivesReload() throws Exception {
        build("b10", 4140, 4000, "fill(0,0,0,2,0,2,'gold_block')");
        hub.result("server.eval", "{\"code\":\"plugin('Craftwire').builds().reload()\"}");
        manage("{\"action\":\"clear\",\"project\":\"b10\"}");
        assertEquals("minecraft:air", blockAt(4141, -50, 4001));
    }

    @Test
    void connectionPassShapesStairsAndFences() throws Exception {
        build("b11", 4160, 4000, "roof({x1:0,z1:0,x2:4,z2:4,y:0,type:'hip',stairs:'oak_stairs'}); line(0,0,8,4,0,8,'oak_fence')");
        assertTrue(blockAt(4160, -50, 4000).contains("shape=outer"), blockAt(4160, -50, 4000));
        assertTrue(blockAt(4162, -50, 4008).contains("east=true"));
    }

    @Test
    void rotationOfStatesMatchesVanilla() throws Exception {
        build("b12", 4180, 4000, "rotate(1, () => { set(0,0,0,'oak_log[axis=x]'); set(0,0,-2,'rail[shape=north_south]'); set(0,0,-4,'oak_door[facing=north,half=lower]'); set(0,1,-4,'oak_door[facing=north,half=upper]'); })");
        assertTrue(blockAt(4180, -50, 4000).contains("axis=z"));
        assertTrue(blockAt(4182, -50, 4000).contains("shape=east_west"));
        assertTrue(blockAt(4184, -50, 4000).contains("facing=east"));
    }

    @Test
    void outsideHeightIsClippedWithAWarning() throws Exception {
        JsonObject r = build("{\"project\":\"b13\",\"origin\":{\"x\":4200,\"y\":318,\"z\":4000},\"code\":\"fill(0,0,0,0,5,0,'stone')\"}");
        assertEquals(2, r.get("changed").getAsInt());
        assertTrue(r.get("warnings").toString().contains("outside the build height"));
    }

    @Test
    void listShowsProjectsAndParts() throws Exception {
        build("b14", 4220, 4000, "set(0,0,0,'stone')");
        assertTrue(manage("{\"action\":\"list\"}").toString().contains("b14"));
    }

    @Test
    void newProjectNeedsAnOrigin() throws Exception {
        JsonObject e = hub.error("build.run", "{\"project\":\"b15\",\"code\":\"set(0,0,0,'stone')\"}");
        assertEquals("BAD_ARGUMENT", e.get("code").getAsString());
    }

    @Test
    void queryLayersPasteBack() throws Exception {
        build("b16", 4240, 4000, "fill(0,0,0,3,2,3, mix({stone:1, glass:1, oak_planks:1})); set(1,1,1,'oak_stairs[facing=east]')");
        JsonObject q = hub.result("world.query", "{\"action\":\"region\",\"encoding\":\"layers\",\"min\":{\"x\":4240,\"y\":-50,\"z\":4000},\"max\":{\"x\":4243,\"y\":-48,\"z\":4003}}").getAsJsonObject();
        String code = "layers(0,0,0," + q.get("legend") + "," + q.get("layers") + ")";
        build("b17", 4250, 4000, code);
        JsonObject copy = hub.result("world.query", "{\"action\":\"region\",\"encoding\":\"layers\",\"min\":{\"x\":4250,\"y\":-50,\"z\":4000},\"max\":{\"x\":4253,\"y\":-48,\"z\":4003}}").getAsJsonObject();
        assertEquals(q.get("layers"), copy.get("layers"));
    }
}
