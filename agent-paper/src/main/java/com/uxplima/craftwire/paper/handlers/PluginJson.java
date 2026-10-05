package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.bukkit.plugin.Plugin;

final class PluginJson {
    private PluginJson() {}

    static JsonObject summary(Plugin p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getName());
        o.addProperty("version", p.getPluginMeta().getVersion());
        o.addProperty("enabled", p.isEnabled());
        return o;
    }

    static JsonObject details(Plugin p) {
        var meta = p.getPluginMeta();
        JsonObject o = summary(p);
        o.addProperty("description", meta.getDescription());
        o.add("authors", strings(meta.getAuthors()));
        o.addProperty("website", meta.getWebsite());
        o.addProperty("main", meta.getMainClass());
        o.add("depend", strings(meta.getPluginDependencies()));
        o.add("softDepend", strings(meta.getPluginSoftDependencies()));
        o.add("commands", strings(commands(p)));
        return o;
    }

    @SuppressWarnings("deprecation")   // plugin.yml commands are only exposed through the legacy description
    private static List<String> commands(Plugin p) {
        try {
            return p.getDescription().getCommands().keySet().stream().sorted().toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static JsonArray strings(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }
}
