package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BotActionsIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    @BeforeEach
    void spawn() throws Exception {
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":-2,\"y\":-60,\"z\":-2},\"max\":{\"x\":30,\"y\":-56,\"z\":6},\"block\":\"air\"}");
        hub.result("bot.spawn", "{\"names\":[\"Act\"],\"location\":{\"x\":0.5,\"y\":-60,\"z\":0.5}}");
        Thread.sleep(300);
    }

    @AfterEach
    void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":-2,\"y\":-60,\"z\":-2},\"max\":{\"x\":30,\"y\":-56,\"z\":6},\"block\":\"air\"}");
    }

    JsonObject act(String json) throws Exception {
        return hub.result("bot.action", json).getAsJsonObject();
    }

    @Test
    void chatReachesPluginsAsAPlayerMessage() throws Exception {
        act("{\"bot\":\"Act\",\"action\":\"chat\",\"text\":\"hello from a bot\"}");
        hub.awaitEvent(e -> e.get("type").getAsString().equals("chat") && e.toString().contains("hello from a bot"), 5000);
    }

    @Test
    void commandReturnsTheRepliesTheBotReceived() throws Exception {
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"command\",\"command\":\"/cwfixture\",\"collectMs\":400}");
        assertTrue(r.get("success").getAsBoolean());
        String texts = r.getAsJsonArray("messages").toString();
        assertTrue(texts.contains("fixture: now") && texts.contains("fixture: later"), texts);
        assertTrue(act("{\"bot\":\"Act\",\"action\":\"messages\"}").getAsJsonArray("messages").toString().contains("fixture: later"));
    }

    @Test
    void commandsGoThroughTheCommandPacketSoPluginsCanBlockThem() throws Exception {
        JsonObject blocked = act("{\"bot\":\"Act\",\"action\":\"command\",\"command\":\"/cwfixture blocked\",\"collectMs\":300}");
        assertFalse(blocked.get("success").getAsBoolean(), blocked.toString());
        assertTrue(blocked.get("cancelled").getAsBoolean(), blocked.toString());
        assertTrue(blocked.getAsJsonArray("messages").toString().contains("fixture: blocked"));
        JsonObject unknown = act("{\"bot\":\"Act\",\"action\":\"command\",\"command\":\"/nosuchcommand\",\"collectMs\":300}");
        assertFalse(unknown.get("success").getAsBoolean());
        assertTrue(unknown.get("unknown").getAsBoolean(), unknown.toString());
    }

    @Test
    void moveToWalksToTheTarget() throws Exception {
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"move_to\",\"x\":10.5,\"y\":-60,\"z\":0.5}");
        assertTrue(r.get("reached").getAsBoolean(), r.toString());
        assertEquals(10.5, r.get("x").getAsDouble(), 0.6);
    }

    @Test
    void moveToJumpsOneBlockSteps() throws Exception {
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":5,\"y\":-60,\"z\":-2},\"max\":{\"x\":5,\"y\":-60,\"z\":2},\"block\":\"stone\"}");
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"move_to\",\"x\":10.5,\"y\":-60,\"z\":0.5}");
        assertTrue(r.get("reached").getAsBoolean(), r.toString());
    }

    @Test
    void moveToStopsWhenStuck() throws Exception {
        hub.result("world.edit", "{\"action\":\"fill\",\"min\":{\"x\":5,\"y\":-60,\"z\":-2},\"max\":{\"x\":5,\"y\":-57,\"z\":3},\"block\":\"stone\"}");
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"move_to\",\"x\":10.5,\"y\":-60,\"z\":0.5,\"timeoutMs\":20000}");
        assertEquals("stuck", r.get("reason").getAsString(), r.toString());
    }

    @Test
    void moveToReportsAWrongHeightInsteadOfStuck() throws Exception {
        // Target 5 blocks above the ground: the bot gets under it and says so, rather than "stuck" after 2 s.
        long started = System.currentTimeMillis();
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"move_to\",\"x\":6.5,\"y\":-55,\"z\":0.5}");
        assertEquals("height", r.get("reason").getAsString(), r.toString());
        assertFalse(r.get("reached").getAsBoolean());
        assertEquals(6.5, r.get("x").getAsDouble(), 0.6);
        assertTrue(System.currentTimeMillis() - started < 3500, "should end on arrival, not after the stuck window");
    }

    @Test
    void moveToTimesOut() throws Exception {
        JsonObject r = act("{\"bot\":\"Act\",\"action\":\"move_to\",\"x\":200.5,\"y\":-60,\"z\":0.5,\"timeoutMs\":1000}");
        assertEquals("timeout", r.get("reason").getAsString(), r.toString());
        assertFalse(r.get("reached").getAsBoolean());
    }

    @Test
    void lookGiveSelectAndState() throws Exception {
        JsonObject look = act("{\"bot\":\"Act\",\"action\":\"look\",\"x\":10.5,\"y\":-58.38,\"z\":0.5}");
        assertEquals(-90.0, look.get("yaw").getAsDouble(), 0.5);
        assertEquals(1, act("{\"bot\":\"Act\",\"action\":\"give\",\"item\":\"diamond_sword\"}").get("given").getAsInt());
        act("{\"bot\":\"Act\",\"action\":\"give\",\"item\":\"stone\",\"count\":10}");
        JsonObject sel = act("{\"bot\":\"Act\",\"action\":\"select_hotbar\",\"slot\":1}");
        assertEquals("minecraft:stone", sel.getAsJsonObject("held").get("id").getAsString());
        JsonObject state = act("{\"bot\":\"Act\",\"action\":\"state\"}");
        assertEquals(1, state.get("heldSlot").getAsInt());
        assertEquals(20.0, state.get("health").getAsDouble(), 0.01);
        assertEquals(2, state.getAsJsonArray("inventory").size());
        assertEquals("INVALID_PARAMS", hub.error("bot.action", "{\"bot\":\"Act\",\"action\":\"give\",\"item\":\"not_an_item\"}").get("code").getAsString());
    }

    @Test
    void deadBotRespawns() throws Exception {
        hub.result("server.command", "{\"command\":\"minecraft:kill Act\"}");
        Thread.sleep(2500);
        JsonObject state = act("{\"bot\":\"Act\",\"action\":\"state\"}");
        assertFalse(state.get("dead").getAsBoolean(), state.toString());
        assertEquals(20.0, state.get("health").getAsDouble(), 0.01);
    }

    @Test
    void unknownBotAndUnknownAction() throws Exception {
        assertEquals("BOT_NOT_FOUND", hub.error("bot.action", "{\"bot\":\"Ghost\",\"action\":\"state\"}").get("code").getAsString());
        assertEquals("INVALID_PARAMS", hub.error("bot.action", "{\"bot\":\"Act\",\"action\":\"fly\"}").get("code").getAsString());
    }
}
