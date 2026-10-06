package com.uxplima.craftwire.fixtures;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.api.CraftwireTool;
import com.uxplima.craftwire.api.ToolException;
import com.uxplima.craftwire.api.ToolRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Tools the fixture adds through the Craftwire API, as a real plugin would. */
final class FixtureTools {
    private FixtureTools() {}

    static void register(JavaPlugin plugin) {
        ToolRegistry registry = plugin.getServer().getServicesManager().load(ToolRegistry.class);
        if (registry == null) return;
        registry.register(new CraftwireTool() {
            @Override public String name() { return "greet"; }
            @Override public String description() { return "Greets a name and says how many players are online."; }
            @Override public String inputSchema() {
                return "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"Who to greet\"}},\"required\":[\"name\"]}";
            }
            @Override public String call(String arguments) {
                String name = JsonParser.parseString(arguments).getAsJsonObject().get("name").getAsString();
                JsonObject r = new JsonObject();
                r.addProperty("greeting", "Hello " + name);
                r.addProperty("online", Bukkit.getOnlinePlayers().size());
                r.addProperty("mainThread", Bukkit.isPrimaryThread());
                return r.toString();
            }
        });
        registry.register(new CraftwireTool() {
            @Override public String name() { return "refuse"; }
            @Override public String description() { return "Always refuses, with a hint."; }
            @Override public boolean onGameThread() { return false; }
            @Override public String call(String arguments) {
                throw new ToolException("FIXTURE_REFUSED", "The fixture refuses", "Ask nicely.");
            }
        });
        registry.register(new CraftwireTool() {
            @Override public String name() { return "crash"; }
            @Override public String description() { return "Throws, like a buggy tool."; }
            @Override public String call(String arguments) {
                throw new IllegalStateException("fixture tool crashed");
            }
        });
    }
}
