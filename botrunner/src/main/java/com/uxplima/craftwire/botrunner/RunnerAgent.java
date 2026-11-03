package com.uxplima.craftwire.botrunner;

import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.botrunner.bot.RunnerBots;
import com.uxplima.craftwire.botrunner.handlers.RunnerMethods;
import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.core.Hello;
import com.uxplima.craftwire.core.HubClient;
import com.uxplima.craftwire.core.HubConfig;
import com.uxplima.craftwire.core.LogCapture;
import com.uxplima.craftwire.core.OperationCache;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The runner's connection to the hub: hello as agent kind "botrunner" with the bot kinds it runs and its capacity,
 * then the bot.* methods. Reconnects on its own like the other agents; bots keep playing while the hub is away.
 */
final class RunnerAgent implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger("craftwire-botrunner");

    private final Dispatcher dispatcher = new Dispatcher(new OperationCache(300_000, System::currentTimeMillis));
    private final RunnerBots bots;
    private final String name;
    private final HubClient hub;
    private final LogCapture logs;

    RunnerAgent(RunnerBots bots, String name, Path home) {
        this.bots = bots;
        this.name = name;
        this.logs = LogCapture.install(1000);
        RunnerMethods.register(dispatcher, bots);
        this.hub = new HubClient(() -> HubConfig.load(home), this::hello, dispatcher, new HubClient.Listener() {
            @Override public void onConnected(String instanceId) {
                LOG.info("Connected to the Craftwire hub as {}", instanceId);
                logs.attach((data, time) -> hub().notifyEvent("log", data, time));
            }

            @Override public void onDisconnected(String reason) {
                logs.detach();
                LOG.info("Lost the connection to the hub ({}); reconnecting", reason);
            }

            @Override public void onRefused(String error) {
                LOG.warn("The hub refused this bot runner: {}. Run the runner that matches the hub version.", error);
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

    private Hello hello() {
        long pid = ProcessHandle.current().pid();
        return new Hello("botrunner", RunnerVersion.VERSION, RunnerVersion.MC_VERSIONS, name, null, pid, null,
                bots.kinds().stream().map(BotKind::id).toList(), bots.maxBots());
    }

    @Override
    public void close() {
        hub.close();
        logs.uninstall();
    }
}
