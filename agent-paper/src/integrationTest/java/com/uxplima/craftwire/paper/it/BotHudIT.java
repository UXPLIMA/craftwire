package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** bot.action hud_read: what a real client would show, rebuilt from the packets the bot received. */
class BotHudIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    @BeforeEach
    void spawn() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"Hud\"],\"location\":{\"x\":0.5,\"y\":-60,\"z\":0.5}}");
        Thread.sleep(300);
    }

    @AfterEach
    void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
    }

    JsonObject hud() throws Exception {
        return hub.result("bot.action", "{\"bot\":\"Hud\",\"action\":\"hud_read\"}").getAsJsonObject();
    }

    @Test
    void readsAPluginScoreboardLikeTheClientDrawsIt() throws Exception {
        hub.result("bot.action", "{\"bot\":\"Hud\",\"action\":\"command\",\"command\":\"/cwfixture hud\",\"collectMs\":200}");
        JsonObject hud = hud();

        JsonObject sidebar = hud.getAsJsonObject("sidebar");
        assertEquals("My Lobby", sidebar.get("title").getAsString(), hud.toString());
        JsonArray rows = sidebar.getAsJsonArray("entries");
        assertEquals(3, rows.size(), rows.toString());
        assertEquals("Coins: 42", rows.get(0).getAsJsonObject().get("name").getAsString(), rows.toString());
        assertEquals("Rank: VIP", rows.get(1).getAsJsonObject().get("name").getAsString(), rows.toString());
        assertEquals("", rows.get(2).getAsJsonObject().get("name").getAsString(), "an empty spacer line stays a line");
        assertTrue(rows.get(0).getAsJsonObject().get("shown").isJsonNull(), "numbers are hidden: " + rows);

        JsonObject tab = hud.getAsJsonObject("tab");
        assertEquals("Fixture Network", tab.get("header").getAsString(), tab.toString());
        assertEquals("fixture.example", tab.get("footer").getAsString(), tab.toString());
        JsonObject me = null;
        for (var p : tab.getAsJsonArray("players")) if (p.getAsJsonObject().get("name").getAsString().equals("Hud")) me = p.getAsJsonObject();
        assertNotNull(me, "the bot is listed: " + tab);
        assertEquals("[VIP] Hud", me.get("display").getAsString(), me.toString());
        assertEquals(3, me.get("score").getAsInt(), me.toString());
        assertTrue(hud.getAsJsonArray("tabList").toString().contains("[VIP] Hud"), hud.get("tabList").toString());

        assertEquals("Event", hud.getAsJsonArray("bossbars").get(0).getAsJsonObject().get("name").getAsString(), hud.toString());
        assertEquals("Welcome", hud.get("title").getAsString(), hud.toString());
        assertEquals("to the fixture", hud.get("subtitle").getAsString(), hud.toString());
        assertEquals("Mana 10", hud.get("actionbar").getAsString(), hud.toString());
    }

    @Test
    void aFreshBotSeesTheMainScoreboardAndNoTitle() throws Exception {
        hub.result("server.command", "{\"command\":\"scoreboard objectives add cwmain dummy \\\"Main\\\"\"}");
        hub.result("server.command", "{\"command\":\"scoreboard objectives setdisplay sidebar cwmain\"}");
        hub.result("server.command", "{\"command\":\"scoreboard players set Alpha cwmain 9\"}");
        try {
            JsonObject hud = hud();
            assertEquals("Main", hud.getAsJsonObject("sidebar").get("title").getAsString(), hud.toString());
            assertEquals("Alpha", hud.getAsJsonObject("sidebar").getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
            assertEquals("9", hud.getAsJsonObject("sidebar").getAsJsonArray("entries").get(0).getAsJsonObject().get("shown").getAsString());
            assertTrue(hud.get("title").isJsonNull(), hud.toString());
        } finally {
            hub.result("server.command", "{\"command\":\"scoreboard objectives remove cwmain\"}");
        }
        assertTrue(hud().get("sidebar").isJsonNull(), "objective removal reaches the bot");
    }
}
