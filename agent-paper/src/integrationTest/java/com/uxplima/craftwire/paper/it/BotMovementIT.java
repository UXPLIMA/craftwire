package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * move_to along paths (walls, steps, doors, ladders, water), break_block, and the jump/sneak/sprint/drop/swap
 * verbs, in a course built at x 300-400, z 200-240 of the flat test world (ground at y -61, feet at -60).
 */
class BotMovementIT {
    static ItHub hub;

    @BeforeAll
    static void build() throws Exception {
        hub = ItEnv.get().hub;
        // Never load or generate chunks implicitly: /fill fails on unloaded ones.
        command("minecraft:forceload add 290 180 410 250");
        command("minecraft:fill 290 -60 180 410 -50 250 minecraft:air");
    }

    @AfterAll
    static void tearDown() throws Exception {
        command("minecraft:forceload remove 290 180 410 250");
    }

    @AfterEach
    void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
    }

    static void command(String c) throws Exception {
        JsonObject r = hub.result("server.command", "{\"command\":\"" + c + "\"}").getAsJsonObject();
        assertFalse(r.toString().contains("Unknown") || r.toString().contains("not loaded"), c + " -> " + r);
    }

    static void spawn(String name, double x, double z) throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"" + name + "\"],\"location\":{\"x\":" + x + ",\"y\":-60,\"z\":" + z + "}}");
        Thread.sleep(300);
    }

    static JsonObject act(String bot, String json) throws Exception {
        return hub.result("bot.action", "{\"bot\":\"" + bot + "\"," + json + "}").getAsJsonObject();
    }

    static JsonObject moveTo(String bot, double x, double y, double z, String extra) throws Exception {
        return act(bot, "\"action\":\"move_to\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"timeoutMs\":40000" + extra);
    }

    @Test
    void walksAroundAWallThatAStraightLineCannotPass() throws Exception {
        command("minecraft:fill 305 -60 195 305 -58 215 minecraft:stone");
        spawn("Mvw", 300.5, 205.5);
        JsonObject straight = moveTo("Mvw", 310.5, -60, 205.5, ",\"path\":false,\"timeoutMs\":4000");
        assertFalse(straight.get("reached").getAsBoolean(), straight.toString());

        JsonObject r = moveTo("Mvw", 310.5, -60, 205.5, "");
        assertTrue(r.get("reached").getAsBoolean(), r.toString());
        assertTrue(r.getAsJsonObject("path").get("nodes").getAsInt() > 12, "a detour: " + r);
        assertTrue(r.getAsJsonObject("path").get("complete").getAsBoolean());
    }

    @Test
    void climbsStepsAndComesBackDown() throws Exception {
        command("minecraft:fill 322 -60 200 330 -60 210 minecraft:stone");
        command("minecraft:fill 325 -59 200 330 -59 210 minecraft:stone");
        command("minecraft:setblock 323 -59 205 minecraft:oak_slab");
        spawn("Mvs", 319.5, 205.5);
        JsonObject up = moveTo("Mvs", 327.5, -58, 205.5, "");
        assertTrue(up.get("reached").getAsBoolean(), up.toString());
        assertEquals(-58, up.get("y").getAsDouble(), 0.01, up.toString());
        JsonObject down = moveTo("Mvs", 319.5, -60, 202.5, "");
        assertTrue(down.get("reached").getAsBoolean(), down.toString());
    }

    @Test
    void opensADoorOnTheWayUnlessToldNot() throws Exception {
        // A closed room (3 high inside) whose only way in is the door in its west wall.
        command("minecraft:fill 340 -61 200 346 -57 210 minecraft:stone hollow");
        command("minecraft:setblock 340 -60 205 minecraft:oak_door[half=lower,facing=east]");
        command("minecraft:setblock 340 -59 205 minecraft:oak_door[half=upper,facing=east]");
        spawn("Mvd", 337.5, 205.5);
        JsonObject no = moveTo("Mvd", 343.5, -60, 205.5, ",\"openDoors\":false");
        assertEquals("no_path", no.get("reason").getAsString(), no.toString());
        assertTrue(no.has("closest"), no.toString());

        JsonObject r = moveTo("Mvd", 343.5, -60, 205.5, "");
        assertTrue(r.get("reached").getAsBoolean(), r.toString());
        JsonObject block = hub.result("world.query", "{\"action\":\"block\",\"x\":340,\"y\":-60,\"z\":205}").getAsJsonObject();
        assertTrue(block.toString().contains("open=true"), block.toString());
    }

    @Test
    void climbsALadder() throws Exception {
        command("minecraft:fill 351 -60 195 360 -57 215 minecraft:stone");
        command("minecraft:fill 350 -60 205 350 -57 205 minecraft:ladder[facing=west]");
        spawn("Mvl", 346.5, 205.5);
        JsonObject r = moveTo("Mvl", 353.5, -56, 205.5, "");
        assertTrue(r.get("reached").getAsBoolean(), r.toString());
        assertEquals(-56, r.get("y").getAsDouble(), 0.01, r.toString());
    }

    @Test
    void swimsAcrossWater() throws Exception {
        command("minecraft:fill 370 -62 180 373 -61 240 minecraft:water");
        spawn("Mvb", 367.5, 210.5);
        JsonObject r = moveTo("Mvb", 376.5, -60, 210.5, "");
        assertTrue(r.get("reached").getAsBoolean(), r.toString());
    }

    @Test
    void walksOnIntoChunksThatLoadAsItGoes() throws Exception {
        // View distance 4: the target is beyond the chunks loaded around the bot when it starts.
        spawn("Mvf", 405.5, 230.5);
        JsonObject r = moveTo("Mvf", 495.5, -60, 230.5, ",\"sprint\":true,\"timeoutMs\":60000");
        assertTrue(r.get("reached").getAsBoolean(), r.toString());
    }

    @Test
    void breaksBlocksLikeASurvivalPlayer() throws Exception {
        command("minecraft:setblock 392 -60 205 minecraft:dirt");
        command("minecraft:setblock 392 -60 207 minecraft:bookshelf");
        command("minecraft:setblock 392 -60 203 minecraft:bedrock");
        spawn("Mvx", 390.5, 205.5);
        command("minecraft:gamemode survival Mvx");
        JsonObject dirt = act("Mvx", "\"action\":\"break_block\",\"block\":{\"x\":392,\"y\":-60,\"z\":205}");
        assertTrue(dirt.get("broken").getAsBoolean(), dirt.toString());
        assertTrue(dirt.get("ticks").getAsInt() >= 10, "dirt by hand takes about 15 ticks: " + dirt);
        assertTrue(hub.result("world.query", "{\"action\":\"block\",\"x\":392,\"y\":-60,\"z\":205}").toString().contains("air"));

        JsonObject shelf = act("Mvx", "\"action\":\"break_block\",\"block\":{\"x\":392,\"y\":-60,\"z\":207}");
        assertFalse(shelf.get("broken").getAsBoolean(), shelf.toString());
        assertEquals("BlockBreakEvent", shelf.get("cancelledBy").getAsString(), shelf.toString());

        JsonObject bedrock = act("Mvx", "\"action\":\"break_block\",\"block\":{\"x\":392,\"y\":-60,\"z\":203}");
        assertEquals("unbreakable", bedrock.get("reason").getAsString(), bedrock.toString());

        command("minecraft:setblock 399 -60 205 minecraft:dirt");
        JsonObject far = hub.error("bot.action", "{\"bot\":\"Mvx\",\"action\":\"break_block\",\"block\":{\"x\":399,\"y\":-60,\"z\":205}}");
        assertEquals("OUT_OF_REACH", far.get("code").getAsString());
    }

    @Test
    void sneakSprintDropAndSwapFireTheirEvents() throws Exception {
        spawn("Mve", 395.5, 225.5);
        long start = System.currentTimeMillis();
        assertTrue(act("Mve", "\"action\":\"sneak\",\"on\":true").get("sneaking").getAsBoolean());
        assertFalse(act("Mve", "\"action\":\"sneak\",\"on\":false").get("sneaking").getAsBoolean());
        assertTrue(act("Mve", "\"action\":\"sprint\",\"on\":true").get("sprinting").getAsBoolean());
        act("Mve", "\"action\":\"sprint\",\"on\":false");
        assertTrue(act("Mve", "\"action\":\"jump\"").get("jumped").getAsBoolean());

        act("Mve", "\"action\":\"give\",\"item\":\"cobblestone\",\"count\":3");
        act("Mve", "\"action\":\"select_hotbar\",\"slot\":0");
        JsonObject one = act("Mve", "\"action\":\"drop\"");
        assertEquals(1, one.getAsJsonObject("dropped").get("count").getAsInt(), one.toString());
        assertEquals(2, one.getAsJsonObject("held").get("count").getAsInt(), one.toString());
        JsonObject swapped = act("Mve", "\"action\":\"swap_hands\"");
        assertTrue(swapped.has("offHand") && !swapped.has("mainHand"), swapped.toString());

        for (String type : new String[] {"PlayerToggleSneakEvent", "PlayerToggleSprintEvent", "PlayerDropItemEvent", "PlayerSwapHandItemsEvent"}) {
            JsonArray found = hub.result("events", "{\"action\":\"query\",\"player\":\"Mve\",\"type\":\"" + type + "\",\"since\":" + start + "}")
                    .getAsJsonObject().getAsJsonArray("events");
            assertFalse(found.isEmpty(), type + " fired for the bot");
        }
    }
}
