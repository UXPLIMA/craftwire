package com.uxplima.craftwire.fabric;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.core.Hello;
import com.uxplima.craftwire.core.HubClient;
import com.uxplima.craftwire.core.HubConfig;
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
            @Override public void onDisconnected() { onHubDisconnected(); }
            @Override public void onLog(String message) { LOGGER.debug("[craftwire] {}", message); }
        });
        hub.start();
    }

    private Hello hello() {
        Minecraft mc = Minecraft.getInstance();
        return new Hello("client", VERSION, SharedConstants.getCurrentVersion().name(), mc.getUser().getName());
    }

    public void onHubConnected(String instanceId) {
        connected = true;
        LOGGER.info("[craftwire] connected to hub as {}", instanceId);
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (savedPauseOnLostFocus == null) savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
            mc.options.pauseOnLostFocus = false;   // Claude Code takes focus; the game must keep rendering
        });
    }

    public void onHubDisconnected() {
        connected = false;
        Minecraft.getInstance().execute(() -> {
            if (savedPauseOnLostFocus != null) {
                Minecraft.getInstance().options.pauseOnLostFocus = savedPauseOnLostFocus;
                savedPauseOnLostFocus = null;
            }
        });
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
