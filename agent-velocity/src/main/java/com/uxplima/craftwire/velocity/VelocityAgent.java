package com.uxplima.craftwire.velocity;

import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.core.Hello;
import com.uxplima.craftwire.core.HubClient;
import com.uxplima.craftwire.core.HubConfig;
import com.uxplima.craftwire.core.LogCapture;
import com.uxplima.craftwire.core.OperationCache;
import com.uxplima.craftwire.velocity.proxy.ProxyMethods;
import com.google.gson.JsonObject;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The proxy's connection to the hub: hello as agent kind "proxy", the proxy.* methods, and the proxy log as "log"
 * events (so logs and exceptions work on it as on a server). The hub connection runs on HubClient's own thread.
 */
final class VelocityAgent implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger("craftwire");

    private final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));
    private final ProxyServer proxy;
    private final String version;
    private final LogCapture logs;
    private final HubClient hub;

    VelocityAgent(ProxyServer proxy, String version) {
        this.proxy = proxy;
        this.version = version;
        this.logs = LogCapture.install(1000);
        ProxyMethods.register(dispatcher, proxy);
        this.hub = new HubClient(() -> HubConfig.load(HubConfig.defaultHome()), this::hello, dispatcher, new HubClient.Listener() {
            @Override public void onConnected(String instanceId) {
                LOG.info("Connected to the Craftwire hub as {}", instanceId);
                logs.attach((data, time) -> hub().notifyEvent("log", data, time));
            }

            @Override public void onDisconnected(String reason) {
                logs.detach();
                LOG.info("Lost the connection to the Craftwire hub ({}); reconnecting", reason);
            }

            @Override public void onRefused(String error) {
                LOG.warn("The Craftwire hub refused this proxy: {}. Install the plugin version that matches the hub.", error);
            }

            @Override public void onLog(String message) {
                LOG.debug(message);
            }
        });
    }

    private HubClient hub() {
        return hub;
    }

    void start() {
        hub.start();
    }

    void notifyEvent(String type, JsonObject data) {
        hub.notifyEvent(type, data);
    }

    private Hello hello() {
        String name = System.getProperty("craftwire.name", "velocity");
        String serverDir = Path.of("").toAbsolutePath().toString();
        // A proxy speaks many Minecraft versions; the newest one it accepts is what matters for the bots joining it.
        String mc = ProtocolVersion.MAXIMUM_VERSION.getMostRecentSupportedVersion();
        LOG.debug("Craftwire hello as {} ({} {})", name, proxy.getVersion().getName(), proxy.getVersion().getVersion());
        return new Hello("proxy", version, mc, name, serverDir, ProcessHandle.current().pid());
    }

    @Override
    public void close() {
        hub.close();
        logs.uninstall();
    }
}
