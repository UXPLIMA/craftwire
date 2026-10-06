package com.uxplima.craftwire.paper;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.core.Hello;
import com.uxplima.craftwire.core.HubClient;
import com.uxplima.craftwire.core.HubConfig;
import com.uxplima.craftwire.core.LogCapture;
import com.uxplima.craftwire.core.OperationCache;
import com.uxplima.craftwire.paper.bot.BotManager;
import com.uxplima.craftwire.paper.events.EventTap;
import com.uxplima.craftwire.paper.handlers.EvalHandler;
import com.uxplima.craftwire.paper.handlers.Handlers;
import com.uxplima.craftwire.paper.script.ScriptEngine;
import com.uxplima.craftwire.paper.world.SnapshotStore;
import java.nio.file.Path;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class CraftwirePlugin extends JavaPlugin {
    private final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));
    private LogCapture logs;
    private HubClient hub;
    private AgentConfig config;
    private Sync sync;
    private ScriptEngine scripts;
    private SnapshotStore snapshots;
    private BotManager bots;
    private EventTap events;

    @Override
    public void onLoad() {
        // As early as possible: lines logged while the server starts are replayed to the hub once it connects.
        logs = LogCapture.install(1000);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = AgentConfig.from(getConfig(), serverFolderName());
        sync = new Sync(this);
        getLogger().warning("Craftwire is active — do not run on production servers");
        scripts = new ScriptEngine(getClass().getClassLoader(), EvalHandler.PRELUDE);
        snapshots = new SnapshotStore(getDataFolder().toPath().resolve("snapshots"));
        bots = new BotManager(this);
        bots.start();
        Handlers.registerAll(this);
        getServer().getPluginManager().registerEvents(new EventBridge(this), this);
        if (config.recordEvents()) {
            events = new EventTap(this);
            events.start();
        }
        hub = new HubClient(() -> HubConfig.load(HubConfig.defaultHome()), this::hello, dispatcher, new HubClient.Listener() {
            @Override public void onConnected(String instanceId) { onHubConnected(instanceId); }
            @Override public void onDisconnected(String reason) {
                getLogger().info("Lost the connection to the Craftwire hub (" + reason + "); reconnecting automatically");
                onHubDisconnected();
            }
            @Override public void onRefused(String error) {
                getLogger().warning("The Craftwire hub refused this server: " + error
                        + ". Restart Claude Code, or install the plugin version that matches the hub.");
            }
            @Override public void onLog(String message) { getSLF4JLogger().debug(message); }
        });
        hub.start();
    }

    @Override
    public void onDisable() {
        if (events != null) events.stop();
        if (bots != null) bots.shutdown();
        if (scripts != null) scripts.close();
        if (hub != null) hub.close();
        if (logs != null) logs.uninstall();
    }

    private Hello hello() {
        return new Hello("server", getPluginMeta().getVersion(), Bukkit.getMinecraftVersion(), config.instanceName(),
                Path.of("").toAbsolutePath().toString(), ProcessHandle.current().pid());
    }

    private void onHubConnected(String instanceId) {
        getLogger().info("Connected to the Craftwire hub as " + instanceId);
        scripts.requestReset();   // a new hub session starts with fresh script globals; never wait on a running eval here
        logs.attach((data, time) -> hub.notifyEvent("log", data, time));
    }

    private void onHubDisconnected() {
        logs.detach();
    }

    private static String serverFolderName() {
        Path dir = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        return dir.getFileName() == null ? "server" : dir.getFileName().toString();
    }

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public Sync sync() {
        return sync;
    }

    public AgentConfig agentConfig() {
        return config;
    }

    public ScriptEngine scripts() {
        return scripts;
    }

    /** The event recorder, or null when record-events is off. */
    public EventTap events() {
        return events;
    }

    public BotManager bots() {
        return bots;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    public Path structuresDir() {
        return getDataFolder().toPath().resolve("structures");
    }

    public void emit(String type, JsonObject data) {
        if (hub != null) hub.notifyEvent(type, data);
    }
}
