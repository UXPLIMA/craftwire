package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.paper.AgentConfig;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.Sync;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

public final class EvalHandler {
    /** Globals every eval sees. Wrapped in a function so user code may declare its own Bukkit/Location. */
    public static final String PRELUDE = """
            (() => {
              const Bukkit = Java.type('org.bukkit.Bukkit');
              const Location = Java.type('org.bukkit.Location');
              globalThis.server = Bukkit.getServer();
              globalThis.player = (name) => Bukkit.getPlayerExact(name);
              globalThis.plugin = (name) => Bukkit.getPluginManager().getPlugin(name);
              globalThis.loc = (x, y, z, world) =>
                new Location(world ? Bukkit.getWorld(world) : Bukkit.getWorlds().get(0), x, y, z);
              if (typeof globalThis.print !== 'function') globalThis.print = (...a) => console.log(...a);
            })();
            """;

    private EvalHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, CraftwirePlugin plugin) {
        AgentConfig config = plugin.agentConfig();
        config.require(config.allowEval(), "allow-eval");
        String code = Args.string(p, "code");
        long timeoutMs = Math.clamp(Args.optLong(p, "timeoutMs").orElse(5000L), 100L, 60_000L);
        boolean reset = Args.bool(p, "reset", false);
        Callable<JsonElement> run = () -> {
            if (reset) plugin.scripts().resetSession();
            return plugin.scripts().eval(code, timeoutMs);
        };
        return where(p, plugin.sync(), run).exceptionallyCompose(t -> CompletableFuture.failedFuture(Sync.explainThread(t)));
    }

    /** `asPlayer`: that player's thread; `at`: the thread of that spot (its chunk loaded first); else the global region. */
    private static CompletableFuture<JsonElement> where(JsonObject p, Sync sync, Callable<JsonElement> run) {
        Optional<String> asPlayer = Args.optString(p, "asPlayer");
        if (asPlayer.isPresent()) return sync.entity(CommandHandler.online(asPlayer.get()), run);
        if (!p.has("at") || !p.get("at").isJsonObject()) return sync.global(run);
        JsonObject at = p.getAsJsonObject("at");
        World world = Args.world(at);
        int x = (int) Math.floor(Args.number(at, "x"));
        int z = (int) Math.floor(Args.number(at, "z"));
        return sync.loaded(world, x >> 4, z >> 4, run);
    }
}
