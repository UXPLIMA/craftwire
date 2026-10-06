package com.uxplima.craftwire.fabric;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.core.Hello;
import com.uxplima.craftwire.core.HubClient;
import com.uxplima.craftwire.core.HubConfig;
import com.uxplima.craftwire.core.LogCapture;
import com.uxplima.craftwire.core.OperationCache;
import com.uxplima.craftwire.fabric.handlers.Handlers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
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
        KillSwitch killSwitch = new KillSwitch(this);
        ScreenWatcher screens = new ScreenWatcher(this);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            scheduler.onEndTick();
            killSwitch.onEndTick(mc);
            screens.onEndTick(mc);
        });
        ChatBridge.register(this);
        Indicator.register(this);

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
        if (hub != null) logs.attach((data, time) -> hub.notifyEvent("log", data, time));
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
