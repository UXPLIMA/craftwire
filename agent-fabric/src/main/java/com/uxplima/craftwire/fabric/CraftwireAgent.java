package com.uxplima.craftwire.fabric;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.api.CraftwireEntrypoint;
import com.uxplima.craftwire.core.ExtensionTools;
import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.core.Hello;
import com.uxplima.craftwire.core.HubClient;
import com.uxplima.craftwire.core.HubConfig;
import com.uxplima.craftwire.core.LogCapture;
import com.uxplima.craftwire.core.OperationCache;
import com.uxplima.craftwire.core.profile.ProfileTools;
import com.uxplima.craftwire.fabric.handlers.Handlers;
import com.uxplima.craftwire.fabric.script.ClientEval;
import com.uxplima.craftwire.fabric.video.Recorder;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CraftwireAgent {
    public static final Logger LOGGER = LoggerFactory.getLogger("craftwire");
    public static final String VERSION = FabricLoader.getInstance().getModContainer("craftwire-agent")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");

    private final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));
    private final ClientScheduler scheduler = new ClientScheduler();
    private final ExecutorService extensionThreads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "craftwire-extension");
        t.setDaemon(true);
        return t;
    });
    private final ExtensionTools extensions = new ExtensionTools(new ExtensionTools.GameThread() {
        @Override public <T> CompletableFuture<T> run(Callable<T> work) {
            return Minecraft.getInstance().submit(() -> {
                try {
                    return work.call();
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
        }
    }, extensionThreads, this::sendTools, (msg, t) -> LOGGER.error(msg, t));
    private final ProfileTools profiling = new ProfileTools(new FabricProfiling());
    private final ClientEval eval = new ClientEval();
    private HubClient hub;
    private LogCapture logs;
    private volatile boolean connected;
    private volatile boolean captureInProgress;
    private Boolean savedPauseOnLostFocus;

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public ClientScheduler scheduler() {
        return scheduler;
    }

    public void start() {
        logs = LogCapture.install(1000);
        Handlers.registerAll(this);
        dispatcher.register("ext.call", extensions::call);
        dispatcher.register("profile.run", profiling::profile);
        dispatcher.register("trace.run", profiling::trace);
        dispatcher.register("client.eval", eval::handle);
        registerExtensions();
        KillSwitch killSwitch = new KillSwitch(this);
        ScreenWatcher screens = new ScreenWatcher(this);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            scheduler.onEndTick();
            killSwitch.onEndTick(mc);
            screens.onEndTick(mc);
        });
        ChatBridge.register(this);
        Indicator.register(this);
        // A recording running when the game closes is finished into a playable file first.
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Recorder.INSTANCE.shutdown());

        hub = new HubClient(() -> HubConfig.load(HubConfig.defaultHome()), this::hello, dispatcher, new HubClient.Listener() {
            @Override public void onConnected(String instanceId) { onHubConnected(instanceId); }
            @Override public void onDisconnected(String reason) {
                LOGGER.info("[craftwire] Lost the connection to the Craftwire hub ({}); reconnecting automatically", reason);
                onHubDisconnected();
            }
            @Override public void onRefused(String error) {
                LOGGER.warn("[craftwire] The Craftwire hub refused this client: {}. Restart your AI client, or install the mod version that matches the hub.", error);
            }
            @Override public void onLog(String message) { LOGGER.debug("[craftwire] {}", message); }
        });
        hub.start();
    }

    private Hello hello() {
        Minecraft mc = Minecraft.getInstance();
        String gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().toString();
        return new Hello("client", VERSION, SharedConstants.getCurrentVersion().name(), mc.getUser().getName(), null, null, gameDir);
    }

    public void onHubConnected(String instanceId) {
        connected = true;
        LOGGER.info("[craftwire] connected to hub as {}", instanceId);
        eval.onHubConnected();
        if (hub != null) logs.attach((data, time) -> hub.notifyEvent("log", data, time));
        sendTools();
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (savedPauseOnLostFocus == null) savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus = false;   // Claude Code takes focus; the game must keep rendering
        });
    }

    public void onHubDisconnected() {
        connected = false;
        if (logs != null) logs.detach();
        Minecraft.getInstance().execute(() -> {
            if (savedPauseOnLostFocus != null) {
                Minecraft.getInstance().options.pauseOnLostFocus = savedPauseOnLostFocus;
                savedPauseOnLostFocus = null;
            }
        });
    }

    /** Mods that declare a "craftwire" entrypoint add their tools, named after their mod id. */
    private void registerExtensions() {
        for (EntrypointContainer<CraftwireEntrypoint> c : FabricLoader.getInstance().getEntrypointContainers("craftwire", CraftwireEntrypoint.class)) {
            String modId = c.getProvider().getMetadata().getId();
            try {
                c.getEntrypoint().registerTools(extensions.registryFor(modId));
            } catch (RuntimeException | LinkageError e) {
                LOGGER.error("[craftwire] {} could not register its Craftwire tools", modId, e);
            }
        }
    }

    /** Tells the hub which extension tools this client has (on connect, and whenever they change). */
    private void sendTools() {
        JsonObject d = new JsonObject();
        d.add("tools", extensions.list());
        emit("tools", d);
    }

    public LogCapture logs() {
        return logs;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isCaptureInProgress() {
        return captureInProgress;
    }

    public void setCaptureInProgress(boolean value) {
        captureInProgress = value;
    }

    public void emit(String type, JsonObject data) {
        if (hub != null) hub.notifyEvent(type, data);
    }
}
