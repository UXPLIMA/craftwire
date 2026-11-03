package com.uxplima.craftwire.botrunner.java;

import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.botrunner.bot.BotDriver;
import com.uxplima.craftwire.botrunner.bot.JoinSpec;
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.botrunner.bot.RunnerServices;
import com.uxplima.craftwire.core.AgentError;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Java protocol bots: picks the Minecraft version (the spawn's `version`, else what the server answers to a status
 * ping, remembered per address) and hands the join to that version's bots. Version setup and pings run on virtual
 * threads, never on the runner loop.
 */
public final class JavaDriver implements BotDriver {
    private final JavaVersions versions;
    private final Map<String, String> serverVersions = new ConcurrentHashMap<>();
    private final ExecutorService setup = Executors.newVirtualThreadPerTaskExecutor();

    public JavaDriver(RunnerServices services) {
        this.versions = new JavaVersions(services);
    }

    @Override
    public BotKind kind() {
        return BotKind.JAVA;
    }

    @Override
    public CompletableFuture<RunnerBot> join(JoinSpec spec) {
        return CompletableFuture.supplyAsync(() -> versions.get(version(spec)), setup).thenCompose(f -> f.join(spec));
    }

    private String version(JoinSpec spec) {
        String asked = Params.optString(spec.raw(), "version").orElse(null);
        if (asked != null) return asked;
        String address = spec.host() + ":" + spec.port();
        return serverVersions.computeIfAbsent(address, a -> {
            StatusPing.Version v = StatusPing.ping(spec.host(), spec.port());
            for (String s : versions.supported()) {
                // "26.3", "26.3.1", "Paper 26.3": the release line is what the codecs depend on.
                if (v.name().matches(".*\\b" + s.replace(".", "\\.") + "(\\.\\d+)?\\b.*")) return s;
            }
            throw new AgentError("VERSION_UNSUPPORTED", spec.host() + " runs " + v.name() + " (protocol " + v.protocol() + ")",
                    "Java bots play " + String.join(", ", versions.supported())
                            + "; pass version if the server accepts one of them (e.g. via ViaVersion).");
        });
    }

    @Override
    public void close() {
        setup.shutdownNow();
        versions.close();
    }
}
