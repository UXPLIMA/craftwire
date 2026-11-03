package com.uxplima.craftwire.velocity.proxy;

import com.uxplima.craftwire.core.AgentError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * proxy.command: runs a proxy command (Velocity's own, such as /velocity or /send, or a proxy plugin's) as the
 * console and returns what it replied. Replies that arrive after the command finished (async plugin work) are kept
 * for collectMs more.
 */
final class ProxyCommand {
    private static final long RUN_TIMEOUT_MS = 30_000;

    private final ProxyServer proxy;

    ProxyCommand(ProxyServer proxy) {
        this.proxy = proxy;
    }

    CompletableFuture<JsonElement> run(JsonObject p) {
        String raw = Params.string(p, "command").strip();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        if (command.isBlank()) throw new AgentError("INVALID_PARAMS", "`command` is empty", "Pass a proxy command such as \"velocity info\".");
        long collectMs = Params.optLong(p, "collectMs", 300, 0, 5000);
        CapturingConsole console = new CapturingConsole();
        return proxy.getCommandManager().executeAsync(console, command)
                .orTimeout(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .thenCompose(found -> CompletableFuture.supplyAsync(() -> found, CompletableFuture.delayedExecutor(collectMs, TimeUnit.MILLISECONDS)))
                .thenApply(found -> {
                    String label = command.split(" ", 2)[0];
                    if (!found && console.lines().isEmpty()) {
                        throw new AgentError("UNKNOWN_COMMAND", "The proxy has no command " + label,
                                "Proxy commands are Velocity's (velocity, server, send, glist) and its plugins'; backend commands go to "
                                        + "server_command on that server.");
                    }
                    JsonObject r = new JsonObject();
                    r.addProperty("command", command);
                    r.addProperty("success", found);
                    JsonArray output = new JsonArray();
                    console.lines().forEach(output::add);
                    r.add("output", output);
                    return (JsonElement) r;
                });
    }
}
