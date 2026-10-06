package com.uxplima.craftwire.fabric.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.core.HubConfig;
import com.uxplima.craftwire.core.ScriptRunner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * client_eval: JavaScript on the render thread, with the game at hand. GraalJS is downloaded and started on the
 * first call (off the render thread); globals survive between calls until reset or a new hub session.
 */
public final class ClientEval {
    /** Globals every script sees. Getters, so `player` and `level` are always the current ones. */
    static final String PRELUDE = """
            (() => {
              const Minecraft = Java.type('net.minecraft.client.Minecraft');
              globalThis.mc = Minecraft.getInstance();
              Object.defineProperty(globalThis, 'player', { get: () => mc.player, configurable: true });
              Object.defineProperty(globalThis, 'level', { get: () => mc.level, configurable: true });
              globalThis.screen = () => mc.gui.screen();
              if (typeof globalThis.print !== 'function') globalThis.print = (...a) => console.log(...a);
            })();
            """;

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "craftwire-graaljs");
        t.setDaemon(true);
        return t;
    });
    private volatile ScriptRunner runner;

    public CompletableFuture<JsonElement> handle(JsonObject p) {
        if (!allowed()) {
            throw new AgentError("EVAL_DISABLED", "client_eval is turned off in this game",
                    "It is off with -Dcraftwire.allowEval=false or allow-eval=false in config/craftwire.properties; ask the user.");
        }
        JsonElement c = p.get("code");
        if (c == null || c.isJsonNull()) throw new AgentError("INVALID_PARAMS", "Missing code", "Pass code: the JavaScript to run.");
        String code = c.getAsString();
        long timeoutMs = Math.clamp(p.has("timeoutMs") ? p.get("timeoutMs").getAsLong() : 5000L, 100L, 60_000L);
        boolean reset = p.has("reset") && p.get("reset").getAsBoolean();
        return CompletableFuture.supplyAsync(this::runner, loader)
                .thenCompose(r -> Minecraft.getInstance().submit(() -> {
                    if (reset) r.resetSession();
                    return (JsonElement) r.eval(code, timeoutMs);
                }));
    }

    private ScriptRunner runner() {
        ScriptRunner r = runner;
        if (r == null) {
            r = GraalRuntime.load(HubConfig.defaultHome(), PRELUDE);
            runner = r;
        }
        return r;
    }

    /** A new hub session starts with fresh globals; never waits on a running script. */
    public void onHubConnected() {
        ScriptRunner r = runner;
        if (r != null) r.requestReset();
    }

    static boolean allowed() {
        String prop = System.getProperty("craftwire.allowEval");
        if (prop != null) return Boolean.parseBoolean(prop);
        Path file = FabricLoader.getInstance().getConfigDir().resolve("craftwire.properties");
        if (!Files.isRegularFile(file)) return true;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return true;
        }
        return Boolean.parseBoolean(props.getProperty("allow-eval", "true").strip());
    }
}
