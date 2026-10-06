package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** events: every Bukkit event is recorded with its outcome, including a plugin's own event the first time it fires. */
class EventsIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    @BeforeEach
    void spawn() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"Evt\"],\"location\":{\"x\":0.5,\"y\":-60,\"z\":0.5}}");
        Thread.sleep(300);
    }

    @AfterEach
    void cleanUp() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
    }

    JsonObject events(String json) throws Exception {
        return hub.result("events", json).getAsJsonObject();
    }

    @Test
    void aPluginsOwnEventIsSeenTheFirstTimeItFiresWithItsOutcome() throws Exception {
        long start = System.currentTimeMillis();
        hub.result("bot.action", "{\"bot\":\"Evt\",\"action\":\"command\",\"command\":\"/cwfixture buy\",\"collectMs\":200}");
        JsonArray found = events("{\"action\":\"query\",\"type\":\"FixtureShopEvent\",\"since\":" + start + "}").getAsJsonArray("events");
        assertEquals(1, found.size(), found.toString());
        JsonObject e = found.get(0).getAsJsonObject();
        assertTrue(e.get("cancelled").getAsBoolean(), "the fixture's listener cancelled it: " + e);
        assertEquals("Evt", e.get("player").getAsString());
        assertEquals(30, e.getAsJsonObject("fields").get("price").getAsInt());

        boolean commandCounted = false;
        for (JsonElement c : events("{\"action\":\"summary\",\"since\":" + start + "}").getAsJsonArray("counts")) {
            if (c.getAsJsonObject().get("type").getAsString().equals("PlayerCommandPreprocessEvent")) commandCounted = true;
        }
        assertTrue(commandCounted, "the bot's command shows in the summary");
        JsonArray byPlayer = events("{\"action\":\"query\",\"player\":\"Evt\",\"type\":\"PlayerCommandPreprocessEvent\",\"since\":" + start + "}").getAsJsonArray("events");
        assertEquals("/cwfixture buy", byPlayer.get(0).getAsJsonObject().getAsJsonObject("fields").get("message").getAsString());
    }

    @Test
    void listsWhoListensToAnEvent() throws Exception {
        JsonObject shop = events("{\"action\":\"listeners\",\"type\":\"FixtureShopEvent\"}");
        JsonObject l = shop.getAsJsonArray("listeners").get(0).getAsJsonObject();
        assertEquals("CraftwireFixture", l.get("plugin").getAsString());
        assertEquals("NORMAL", l.get("priority").getAsString());
        assertTrue(shop.get("recorded").getAsBoolean());
        for (JsonElement x : events("{\"action\":\"listeners\",\"type\":\"PlayerJoinEvent\"}").getAsJsonArray("listeners")) {
            assertFalse(x.getAsJsonObject().get("listener").getAsString().endsWith("EventTap"), "the recorder itself is not listed");
        }
        assertEquals("EVENT_NOT_FOUND", hub.error("events", "{\"action\":\"listeners\",\"type\":\"NoSuchEvent\"}").get("code").getAsString());
    }

    @Test
    void busyEventsAreRecordedOnlyWhenWatched() throws Exception {
        assertFalse(events("{\"action\":\"listeners\",\"type\":\"EntityMoveEvent\"}").get("recorded").getAsBoolean());
        events("{\"action\":\"watch\",\"types\":[\"EntityMoveEvent\"]}");
        assertTrue(events("{\"action\":\"listeners\",\"type\":\"EntityMoveEvent\"}").get("recorded").getAsBoolean());
    }
}
