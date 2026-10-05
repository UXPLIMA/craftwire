package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class CommandIT {
    static JsonObject run(String paramsJson) throws Exception {
        return ItEnv.get().hub.result("server.command", paramsJson).getAsJsonObject();
    }

    static JsonObject fail(String paramsJson) throws Exception {
        return ItEnv.get().hub.error("server.command", paramsJson);
    }

    @Test
    void vanillaFeedbackIsReturned() throws Exception {
        JsonObject r = run("{\"command\":\"time query gametime\"}");
        assertTrue(r.get("success").getAsBoolean());
        assertTrue(r.getAsJsonArray("output").get(0).getAsString().startsWith("The game time is"), r.toString());
    }

    @Test
    void leadingSlashIsOptionalAndBukkitCommandsWork() throws Exception {
        assertTrue(run("{\"command\":\"/plugins\"}").getAsJsonArray("output").toString().contains("Craftwire"));
    }

    @Test
    void lateFeedbackIsCollected() throws Exception {
        JsonObject r = run("{\"command\":\"cwfixture\",\"collectMs\":1000}");
        assertEquals("[\"fixture: now\",\"fixture: later\"]", r.getAsJsonArray("output").toString());
    }

    @Test
    void collectMsZeroReturnsOnlyImmediateFeedback() throws Exception {
        JsonObject r = run("{\"command\":\"cwfixture\",\"collectMs\":0}");
        assertEquals("[\"fixture: now\"]", r.getAsJsonArray("output").toString());
    }

    @Test
    void unknownCommandIsAnError() throws Exception {
        JsonObject e = fail("{\"command\":\"nosuchcmd arg\"}");
        assertEquals("UNKNOWN_COMMAND", e.get("code").getAsString());
        assertTrue(e.get("message").getAsString().contains("nosuchcmd"));
    }

    @Test
    void aThrowingVanillaCommandIsCommandFailed() throws Exception {
        // 26.2 moved day time to timelines; the old form throws inside the command.
        JsonObject e = fail("{\"command\":\"time query daytime\"}");
        assertEquals("COMMAND_FAILED", e.get("code").getAsString());
        assertTrue(e.get("message").getAsString().contains("daytime"), e.toString());
    }

    @Test
    void asPlayerNeedsAnOnlinePlayer() throws Exception {
        assertEquals("PLAYER_NOT_FOUND", fail("{\"command\":\"list\",\"asPlayer\":\"Nobody\"}").get("code").getAsString());
    }
}
