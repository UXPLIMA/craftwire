package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class PluginManageIT {
    static JsonObject manage(String paramsJson) throws Exception {
        return ItEnv.get().hub.result("plugin.manage", paramsJson).getAsJsonObject();
    }

    @Test
    void listIncludesEveryPlugin() throws Exception {
        String list = manage("{\"action\":\"list\"}").getAsJsonArray("plugins").toString();
        assertTrue(list.contains("\"name\":\"Craftwire\""), list);
        assertTrue(list.contains("\"name\":\"CraftwireFixture\""), list);
    }

    @Test
    void infoShowsVersionAndCommands() throws Exception {
        JsonObject info = manage("{\"action\":\"info\",\"name\":\"craftwirefixture\"}");
        assertEquals("CraftwireFixture", info.get("name").getAsString());
        assertEquals(System.getProperty("craftwire.version"), info.get("version").getAsString());
        assertEquals("[\"cwfixture\"]", info.getAsJsonArray("commands").toString());
    }

    @Test
    void disableThenEnableRoundTrip() throws Exception {
        ItHub hub = ItEnv.get().hub;
        JsonObject off = manage("{\"action\":\"disable\",\"name\":\"CraftwireFixture\"}");
        assertFalse(off.get("enabled").getAsBoolean());
        assertTrue(off.get("changed").getAsBoolean());
        assertEquals("COMMAND_FAILED", hub.error("server.command", "{\"command\":\"cwfixture\"}").get("code").getAsString());
        JsonObject on = manage("{\"action\":\"enable\",\"name\":\"CraftwireFixture\"}");
        assertTrue(on.get("enabled").getAsBoolean());
        assertTrue(hub.result("server.command", "{\"command\":\"cwfixture\",\"collectMs\":0}").toString().contains("fixture: now"));
        assertFalse(manage("{\"action\":\"enable\",\"name\":\"CraftwireFixture\"}").get("changed").getAsBoolean());
    }

    @Test
    void craftwireCannotDisableItself() throws Exception {
        assertEquals("CANNOT_DISABLE_SELF", ItEnv.get().hub.error("plugin.manage", "{\"action\":\"disable\",\"name\":\"Craftwire\"}").get("code").getAsString());
    }

    @Test
    void unknownPluginIsNotFound() throws Exception {
        assertEquals("PLUGIN_NOT_FOUND", ItEnv.get().hub.error("plugin.manage", "{\"action\":\"info\",\"name\":\"Nope\"}").get("code").getAsString());
    }
}
