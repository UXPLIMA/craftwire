package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BotGuiIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    @BeforeEach
    void spawn() throws Exception {
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":20,\"y\":-60,\"z\":20},\"max\":{\"x\":26,\"y\":-56,\"z\":26},\"block\":\"air\"}");
        hub.result("bot.spawn", "{\"names\":[\"Gui\"],\"location\":{\"x\":22.5,\"y\":-60,\"z\":22.5}}");
        Thread.sleep(300);
    }

    @AfterEach
    void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
        hub.result("server.command", "{\"command\":\"minecraft:kill @e[type=pig]\"}");
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":20,\"y\":-60,\"z\":20},\"max\":{\"x\":26,\"y\":-56,\"z\":26},\"block\":\"air\"}");
    }

    JsonObject act(String json) throws Exception {
        return hub.result("bot.action", json).getAsJsonObject();
    }

    @Test
    void guiReadShowsThePluginMenu() throws Exception {
        assertFalse(act("{\"bot\":\"Gui\",\"action\":\"gui_read\"}").get("open").getAsBoolean());
        act("{\"bot\":\"Gui\",\"action\":\"command\",\"command\":\"cwfixture menu\",\"collectMs\":100}");
        JsonObject gui = act("{\"bot\":\"Gui\",\"action\":\"gui_read\"}");
        assertTrue(gui.get("open").getAsBoolean());
        assertEquals("Fixture Menu", gui.get("title").getAsString());
        JsonObject first = gui.getAsJsonArray("slots").get(0).getAsJsonObject();
        assertEquals(4, first.get("slot").getAsInt());
        assertEquals("minecraft:diamond", first.get("id").getAsString());
        assertEquals("menu", first.get("container").getAsString());
    }

    @Test
    void guiClickSeesThePluginsReaction() throws Exception {
        act("{\"bot\":\"Gui\",\"action\":\"command\",\"command\":\"cwfixture menu\",\"collectMs\":100}");
        JsonObject r = act("{\"bot\":\"Gui\",\"action\":\"gui_click\",\"slot\":4}");
        assertFalse(r.getAsJsonObject("gui").get("open").getAsBoolean(), r.toString()); // the plugin closed it a tick later
        assertTrue(act("{\"bot\":\"Gui\",\"action\":\"messages\"}").toString().contains("fixture: clicked 4"));
        assertEquals("NO_SCREEN_OPEN", hub.error("bot.action", "{\"bot\":\"Gui\",\"action\":\"gui_click\",\"slot\":4}").get("code").getAsString());
    }

    @Test
    void theBotsOwnInventoryCanBeReadAndClickedWithoutAMenu() throws Exception {
        act("{\"bot\":\"Gui\",\"action\":\"give\",\"item\":\"stone\",\"count\":5}");
        JsonObject inv = act("{\"bot\":\"Gui\",\"action\":\"gui_read\",\"inventory\":true}");
        assertEquals("Inventory", inv.get("title").getAsString(), inv.toString());
        assertEquals(36, inv.getAsJsonArray("slots").get(0).getAsJsonObject().get("slot").getAsInt(), "hotbar slot 0 is raw slot 36");
        // shift-click moves the stack from the hotbar into the main inventory, as for a player with E open
        JsonObject r = act("{\"bot\":\"Gui\",\"action\":\"gui_click\",\"inventory\":true,\"slot\":36,\"click\":\"shift\"}");
        JsonObject moved = r.getAsJsonObject("gui").getAsJsonArray("slots").get(0).getAsJsonObject();
        assertTrue(moved.get("slot").getAsInt() >= 9 && moved.get("slot").getAsInt() <= 35, r.toString());
        assertEquals(5, moved.get("count").getAsInt());
        assertEquals("NO_SCREEN_OPEN", hub.error("bot.action", "{\"bot\":\"Gui\",\"action\":\"gui_click\",\"slot\":36}").get("code").getAsString());
    }

    @Test
    void guiCloseAndSlotRange() throws Exception {
        act("{\"bot\":\"Gui\",\"action\":\"command\",\"command\":\"cwfixture menu\",\"collectMs\":100}");
        assertEquals("SLOT_OUT_OF_RANGE", hub.error("bot.action", "{\"bot\":\"Gui\",\"action\":\"gui_click\",\"slot\":999}").get("code").getAsString());
        assertTrue(act("{\"bot\":\"Gui\",\"action\":\"gui_close\"}").get("closed").getAsBoolean());
        assertFalse(act("{\"bot\":\"Gui\",\"action\":\"gui_read\"}").get("open").getAsBoolean());
    }

    @Test
    void useOnABlockPlacesTheHeldBlock() throws Exception {
        act("{\"bot\":\"Gui\",\"action\":\"give\",\"item\":\"stone\",\"count\":4}");
        JsonObject r = act("{\"bot\":\"Gui\",\"action\":\"use\",\"block\":{\"x\":24,\"y\":-61,\"z\":22},\"face\":\"up\"}");
        assertEquals("block", r.get("used").getAsString());
        Thread.sleep(100);
        JsonObject b = hub.result("world.query", "{\"action\":\"block\",\"x\":24,\"y\":-60,\"z\":22}").getAsJsonObject();
        if (!"minecraft:stone".equals(b.get("block").getAsString())) {
            // Seen once on CI only: collect what the bot and the world looked like.
            Thread.sleep(500);
            fail("no stone placed: " + b + " | after 500 ms: " + hub.result("world.query", "{\"action\":\"block\",\"x\":24,\"y\":-60,\"z\":22}")
                    + " | below: " + hub.result("world.query", "{\"action\":\"block\",\"x\":24,\"y\":-61,\"z\":22}")
                    + " | state: " + act("{\"bot\":\"Gui\",\"action\":\"state\"}")
                    + " | messages: " + act("{\"bot\":\"Gui\",\"action\":\"messages\"}"));
        }
        assertEquals("OUT_OF_REACH", hub.error("bot.action", "{\"bot\":\"Gui\",\"action\":\"use\",\"block\":{\"x\":60,\"y\":-61,\"z\":60}}").get("code").getAsString());
    }

    @Test
    void aRespawnedBotCanStillUseBlocks() throws Exception {
        // The server ignores use/attack until the "client" says it has loaded, again after every death.
        hub.result("server.command", "{\"command\":\"minecraft:kill Gui\"}");
        Thread.sleep(2500);
        hub.result("server.command", "{\"command\":\"minecraft:tp Gui 22.5 -60 22.5\"}");
        act("{\"bot\":\"Gui\",\"action\":\"give\",\"item\":\"stone\"}");
        act("{\"bot\":\"Gui\",\"action\":\"use\",\"block\":{\"x\":24,\"y\":-61,\"z\":22},\"face\":\"up\"}");
        Thread.sleep(100);
        JsonObject b = hub.result("world.query", "{\"action\":\"block\",\"x\":24,\"y\":-60,\"z\":22}").getAsJsonObject();
        assertEquals("minecraft:stone", b.get("block").getAsString(), b.toString());
    }

    @Test
    void attackDamagesTheNearestEntityOfAType() throws Exception {
        hub.result("server.command", "{\"command\":\"minecraft:summon pig 24.5 -60 22.5 {NoAI:1b}\"}");
        Thread.sleep(200);
        JsonObject r;
        try {
            r = act("{\"bot\":\"Gui\",\"action\":\"attack\",\"type\":\"pig\"}");
        } catch (AssertionError e) {
            // Failed once on CI with the target 4.4 blocks away: show where the bot and the pigs really were.
            JsonObject state = act("{\"bot\":\"Gui\",\"action\":\"state\"}");
            throw new AssertionError(e.getMessage() + " | bot at " + state.get("position")
                    + " | pigs: " + hub.result("world.query", "{\"action\":\"entities\",\"type\":\"pig\"}"), e);
        }
        assertEquals("minecraft:pig", r.getAsJsonObject("target").get("type").getAsString());
        assertTrue(r.get("healthAfter").getAsDouble() < r.get("healthBefore").getAsDouble(), r.toString());
        assertEquals("ENTITY_NOT_FOUND", hub.error("bot.action", "{\"bot\":\"Gui\",\"action\":\"attack\",\"type\":\"cow\"}").get("code").getAsString());
    }
}
