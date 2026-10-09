package com.uxplima.craftwire.paper.it;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Tools other plugins add through the Craftwire API: announced to the hub, callable, removed with their plugin. */
class ExtensionsIT {
    static List<String> names(JsonObject toolsEvent) {
        List<String> out = new ArrayList<>();
        for (JsonElement t : toolsEvent.getAsJsonObject("data").getAsJsonArray("tools")) out.add(t.getAsJsonObject().get("name").getAsString());
        return out;
    }

    static JsonObject latestTools(ItHub hub) throws Exception {
        hub.awaitEvent(e -> "tools".equals(e.get("type").getAsString()), 10_000);
        JsonObject last = null;
        for (JsonObject e : hub.events) if ("tools".equals(e.get("type").getAsString())) last = e;
        return last;
    }

    @Test
    void aPluginsToolsAreAnnouncedWithTheirSchemaAndCalledOnTheMainThread() throws Exception {
        ItHub hub = ItEnv.get().hub;
        JsonObject tools = latestTools(hub);
        assertEquals(List.of("craftwirefixture_crash", "craftwirefixture_greet", "craftwirefixture_refuse"), names(tools));
        JsonObject greet = tools.getAsJsonObject("data").getAsJsonArray("tools").get(1).getAsJsonObject();
        assertEquals("craftwirefixture", greet.get("namespace").getAsString());
        assertEquals("[\"name\"]", greet.getAsJsonObject("inputSchema").getAsJsonArray("required").toString());

        JsonObject r = hub.result("ext.call", "{\"tool\":\"craftwirefixture_greet\",\"args\":{\"name\":\"Steve\"}}").getAsJsonObject();
        assertEquals("Hello Steve", r.get("greeting").getAsString());
        assertTrue(r.get("mainThread").getAsBoolean());
    }

    @Test
    void failuresBecomeCraftwireErrors() throws Exception {
        ItHub hub = ItEnv.get().hub;
        JsonObject refused = hub.error("ext.call", "{\"tool\":\"craftwirefixture_refuse\",\"args\":{}}");
        assertEquals("FIXTURE_REFUSED", refused.get("code").getAsString());
        assertEquals("Ask nicely.", refused.get("hint").getAsString());

        JsonObject crashed = hub.error("ext.call", "{\"tool\":\"craftwirefixture_crash\",\"args\":{}}");
        assertEquals("EXTENSION_FAILED", crashed.get("code").getAsString());
        assertTrue(crashed.get("message").getAsString().contains("fixture tool crashed"), crashed.toString());
        JsonObject log = hub.awaitLog(m -> m.contains("craftwirefixture_crash failed"), 5000);
        assertTrue(log.getAsJsonObject("data").get("thrown").getAsString().contains("IllegalStateException: fixture tool crashed"), log.toString());

        assertEquals("EXTENSION_NOT_FOUND", hub.error("ext.call", "{\"tool\":\"nobody_nothing\",\"args\":{}}").get("code").getAsString());
    }

    @Test
    void aDisabledPluginsToolsAreRemovedAndComeBackWithIt() throws Exception {
        Assumptions.assumeFalse(ItEnv.folia(), "Folia cannot disable plugins at runtime (plugin_manage refuses)");
        ItHub hub = ItEnv.get().hub;
        hub.result("plugin.manage", "{\"action\":\"disable\",\"name\":\"CraftwireFixture\"}");
        try {
            JsonArray none = waitForTools(hub, 0);
            assertEquals(0, none.size(), none.toString());
            assertEquals("EXTENSION_NOT_FOUND", hub.error("ext.call", "{\"tool\":\"craftwirefixture_greet\",\"args\":{\"name\":\"x\"}}").get("code").getAsString());
        } finally {
            hub.result("plugin.manage", "{\"action\":\"enable\",\"name\":\"CraftwireFixture\"}");
        }
        assertEquals(3, waitForTools(hub, 3).size());
    }

    static JsonArray waitForTools(ItHub hub, int count) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            JsonArray t = latestTools(hub).getAsJsonObject("data").getAsJsonArray("tools");
            if (t.size() == count) return t;
            Thread.sleep(100);
        }
        return latestTools(hub).getAsJsonObject("data").getAsJsonArray("tools");
    }
}
