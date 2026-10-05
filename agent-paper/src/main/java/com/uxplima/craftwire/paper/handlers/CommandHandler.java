package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class CommandHandler {
    private CommandHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, Sync sync) {
        String raw = Args.string(p, "command").strip();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        if (command.isBlank()) throw Args.invalid("`command` is empty");
        long collectMs = Math.clamp(Args.optLong(p, "collectMs").orElse(250L), 0L, 5000L);
        Optional<String> asPlayer = Args.optString(p, "asPlayer");
        List<String> output = new CopyOnWriteArrayList<>();
        CompletableFuture<JsonObject> dispatched = sync.global(() -> dispatch(command, asPlayer, output));
        long wait = asPlayer.isPresent() ? 0 : collectMs;
        return dispatched
                .thenCompose(r -> CompletableFuture.supplyAsync(() -> r, CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS)))
                .thenApply(r -> {
                    JsonArray lines = new JsonArray();
                    output.forEach(lines::add);
                    r.add("output", lines);
                    return (JsonElement) r;
                });
    }

    private static JsonObject dispatch(String command, Optional<String> asPlayer, List<String> output) {
        CommandSender sender;
        if (asPlayer.isPresent()) {
            Player player = Bukkit.getPlayerExact(asPlayer.get());
            if (player == null) {
                throw new AgentError("PLAYER_NOT_FOUND", "No online player named " + asPlayer.get(),
                        "Use world_query {action:'players'} to see who is online.");
            }
            sender = player;
        } else {
            sender = Bukkit.createCommandSender(c -> output.add(PlainTextComponentSerializer.plainText().serialize(c)));
        }
        String label = command.split(" ", 2)[0];
        boolean known = Bukkit.getCommandMap().getCommand(label) != null;
        boolean success;
        try {
            success = Bukkit.dispatchCommand(sender, command);
        } catch (CommandException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new AgentError("COMMAND_FAILED", String.valueOf(cause.getMessage()),
                    "Fix the command's arguments. Vanilla syntax changes between versions (e.g. gamerules are snake_case in 26.x).");
        }
        if (!success && !known) {
            throw new AgentError("UNKNOWN_COMMAND", "Unknown command: " + label,
                    "Check the spelling or plugin_manage {action:'list'}. Use the minecraft: prefix when a plugin overrides a vanilla command.");
        }
        JsonObject r = new JsonObject();
        r.addProperty("command", command);
        r.addProperty("success", success);
        if (asPlayer.isPresent()) {
            r.addProperty("sender", asPlayer.get());
            r.addProperty("note", "Feedback went to the player's chat; read it with the client chat tool.");
        }
        return r;
    }
}
