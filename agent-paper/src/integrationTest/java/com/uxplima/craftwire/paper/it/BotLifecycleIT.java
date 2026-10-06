package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BotLifecycleIT {
    static ItHub hub;

    @BeforeAll
    static void connect() throws Exception {
        hub = ItEnv.get().hub;
    }

    @AfterEach
    void removeBots() throws Exception {
        hub.result("bot.remove", "{\"all\":true}");
    }

    JsonArray players() throws Exception {
        return hub.result("world.query", "{\"action\":\"players\"}").getAsJsonObject().getAsJsonArray("players");
    }

    JsonObject player(String name) throws Exception {
        for (JsonElement e : players()) if (e.getAsJsonObject().get("name").getAsString().equals(name)) return e.getAsJsonObject();
        return null;
    }

    @Test
    void spawnJoinsAsRealPlayersAndRemoveQuits() throws Exception {
        JsonArray bots = hub.result("bot.spawn", "{\"count\":2,\"namePrefix\":\"It\"}").getAsJsonObject().getAsJsonArray("bots");
        assertEquals("It1", bots.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("It2", bots.get(1).getAsJsonObject().get("name").getAsString());
        hub.awaitEvent(e -> e.toString().contains("\"action\":\"join\"") && e.toString().contains("It2"), 5000);
        assertTrue(player("It1").get("bot").getAsBoolean());
        JsonArray removed = hub.result("bot.remove", "{\"all\":true}").getAsJsonObject().getAsJsonArray("removed");
        assertEquals(2, removed.size());
        hub.awaitEvent(e -> e.toString().contains("\"action\":\"quit\"") && e.toString().contains("It1"), 5000);
        assertEquals(0, players().size());
    }

    @Test
    void botsFallAndStandOnTheGround() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"ItFall\"],\"location\":{\"x\":3.5,\"y\":-54,\"z\":3.5}}");
        Thread.sleep(2000);
        JsonObject p = player("ItFall");
        assertEquals(-60.0, p.get("y").getAsDouble(), 0.01, p.toString()); // flat world surface
    }

    @Test
    void nameTakenAndBadNames() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"ItTwin\"]}");
        assertEquals("NAME_TAKEN", hub.error("bot.spawn", "{\"names\":[\"ittwin\"]}").get("code").getAsString());
        assertEquals("INVALID_PARAMS", hub.error("bot.spawn", "{\"names\":[\"x\"]}").get("code").getAsString());
        assertEquals("BOT_NOT_FOUND", hub.error("bot.remove", "{\"name\":\"Nobody\"}").get("code").getAsString());
    }

    @Test
    void removingABotInTheTickItWasKickedLeavesItOnce() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"ItRace\"]}");
        hub.result("server.eval", "{\"code\":\"server.dispatchCommand(server.getConsoleSender(), 'minecraft:kick ItRace bye');"
                + " plugin('Craftwire').bots().remove('ItRace'); 'ok'\",\"reset\":true}");
        Thread.sleep(500);
        assertNull(player("ItRace"));
        long quits = hub.events.stream().filter(e -> e.toString().contains("\"action\":\"quit\"") && e.toString().contains("ItRace")).count();
        assertEquals(1, quits, "exactly one quit event");
        assertTrue(hub.events.stream().noneMatch(e -> e.toString().contains("\"level\":\"ERROR\"") && e.toString().contains("ItRace")), "no errors");
    }

    @Test
    void kickedBotLeaves() throws Exception {
        hub.result("bot.spawn", "{\"names\":[\"ItKick\"]}");
        hub.result("server.command", "{\"command\":\"minecraft:kick ItKick bye\"}");
        Thread.sleep(1000);
        assertNull(player("ItKick"));
        assertEquals("BOT_NOT_FOUND", hub.error("bot.remove", "{\"name\":\"ItKick\"}").get("code").getAsString());
    }
}
