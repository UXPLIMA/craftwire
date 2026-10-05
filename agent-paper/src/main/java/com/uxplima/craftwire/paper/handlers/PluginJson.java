package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonObject;
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
}
