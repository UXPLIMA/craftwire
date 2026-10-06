package com.uxplima.craftwire.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.api.CraftwireTool;
import com.uxplima.craftwire.api.ToolRegistry;
import com.uxplima.craftwire.core.ExtensionTools;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/**
 * Tools other plugins add: the {@link ToolRegistry} service, whose tools are named after the plugin that registers
 * them (found by its class loader) and removed when that plugin is disabled.
 */
public final class PaperExtensions implements Listener {
    private final CraftwirePlugin plugin;
    private final ExecutorService background = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "craftwire-extension");
        t.setDaemon(true);
        return t;
    });
    private final ExtensionTools tools;

    PaperExtensions(CraftwirePlugin plugin, Runnable onChange) {
        this.plugin = plugin;
        this.tools = new ExtensionTools(new ExtensionTools.GameThread() {
            @Override public <T> CompletableFuture<T> run(Callable<T> work) { return plugin.sync().global(work); }
        }, background, onChange, (msg, t) -> plugin.getLogger().log(Level.SEVERE, msg, t));
    }

    void start() {
        Bukkit.getServicesManager().register(ToolRegistry.class, new ToolRegistry() {
            @Override public void register(CraftwireTool tool) { tools.register(namespaceOf(tool), tool); }
            @Override public void unregister(CraftwireTool tool) { tools.unregister(namespaceOf(tool), tool); }
        }, plugin, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void stop() {
        background.shutdownNow();
    }

    JsonArray list() {
        return tools.list();
    }

    public CompletableFuture<JsonElement> call(JsonObject params) {
        return tools.call(params);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisable(PluginDisableEvent e) {
        ClassLoader loader = e.getPlugin().getClass().getClassLoader();
        tools.unregisterIf(t -> t.getClass().getClassLoader() == loader);
    }

    private static String namespaceOf(CraftwireTool tool) {
        ClassLoader loader = tool.getClass().getClassLoader();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p.getClass().getClassLoader() == loader) return ExtensionTools.namespace(p.getName());
        }
        throw new IllegalArgumentException("Craftwire tools must be classes of the plugin that registers them: "
                + tool.getClass().getName() + " belongs to no plugin");
    }
}
