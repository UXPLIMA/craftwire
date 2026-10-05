package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

final class PluginManageHandler {
    private PluginManageHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, CraftwirePlugin self) {
        String action = Args.string(p, "action");
        return self.sync().global(() -> switch (action) {
            case "list" -> list();
            case "info" -> PluginJson.details(find(Args.string(p, "name")));
            case "enable" -> setEnabled(find(Args.string(p, "name")), true, self);
            case "disable" -> setEnabled(find(Args.string(p, "name")), false, self);
            default -> throw Args.invalid("Unknown action: " + action);
        });
    }

    private static JsonElement list() {
        JsonArray plugins = new JsonArray();
        Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .sorted(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(pl -> {
                    JsonObject o = PluginJson.summary(pl);
                    o.addProperty("description", pl.getPluginMeta().getDescription());
                    JsonArray authors = new JsonArray();
                    pl.getPluginMeta().getAuthors().forEach(authors::add);
                    o.add("authors", authors);
                    plugins.add(o);
                });
        JsonObject r = new JsonObject();
        r.add("plugins", plugins);
        return r;
    }

    private static Plugin find(String name) {
        return Arrays.stream(Bukkit.getPluginManager().getPlugins())
                .filter(pl -> pl.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new AgentError("PLUGIN_NOT_FOUND", "No plugin named " + name,
                        "Use plugin_manage {action:'list'} for the installed plugins."));
    }

    private static JsonElement setEnabled(Plugin target, boolean enable, Plugin self) {
        if (target == self) {
            throw new AgentError("CANNOT_DISABLE_SELF", "Craftwire cannot change its own state",
                    "To stop Craftwire, remove it from plugins/ and restart the server.");
        }
        boolean before = target.isEnabled();
        if (enable && !before) Bukkit.getPluginManager().enablePlugin(target);
        if (!enable && before) Bukkit.getPluginManager().disablePlugin(target);
        JsonObject r = PluginJson.summary(target);
        r.addProperty("changed", before != target.isEnabled());
        return r;
    }
}
