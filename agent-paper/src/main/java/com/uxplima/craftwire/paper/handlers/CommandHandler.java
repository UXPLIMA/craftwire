package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.core.LogCapture;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class CommandHandler {
    /** Where the console's replies are logged: plugin messages go through System.out, vanilla feedback through MinecraftServer. */
    private static final Set<String> CONSOLE_LOGGERS = Set.of("", "net.minecraft.server.MinecraftServer");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");
    /** 26.3 logs the console's chat messages with this prefix. */
    private static final String SYSTEM_CHAT = "System chat: ";
    private static final String VANILLA_WRAPPER = "org.bukkit.craftbukkit.command.VanillaCommandWrapper";

    private CommandHandler() {}

    static CompletableFuture<JsonElement> handle(JsonObject p, Sync sync, LogCapture logs) {
        String raw = Args.string(p, "command").strip();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        if (command.isBlank()) throw Args.invalid("`command` is empty");
        long collectMs = Math.clamp(Args.optLong(p, "collectMs").orElse(250L), 0L, 5000L);
        Optional<String> asPlayer = Args.optString(p, "asPlayer");
        List<String> output = new CopyOnWriteArrayList<>();
        AtomicReference<AutoCloseable> listening = new AtomicReference<>();
        // A player's command runs on that player's thread (Folia: its region), the console's on the global region.
        CompletableFuture<JsonObject> dispatched = asPlayer.isPresent()
                ? sync.entity(online(asPlayer.get()), () -> dispatch(command, asPlayer, output, logs, listening))
                : sync.global(() -> dispatch(command, asPlayer, output, logs, listening));
        dispatched = dispatched.exceptionallyCompose(t -> CompletableFuture.failedFuture(Sync.explainThread(t)));
        long wait = asPlayer.isPresent() ? 0 : collectMs;
        return dispatched
                .thenCompose(r -> CompletableFuture.supplyAsync(() -> r, CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS)))
                .whenComplete((r, t) -> stopListening(listening))
                .thenApply(r -> {
                    JsonArray lines = new JsonArray();
                    output.forEach(lines::add);
                    r.add("output", lines);
                    return (JsonElement) r;
                });
    }

    private static void stopListening(AtomicReference<AutoCloseable> listening) {
        AutoCloseable l = listening.getAndSet(null);
        if (l == null) return;
        try {
            l.close();
        } catch (Exception ignored) {
            // removing a listener cannot fail
        }
    }

    /**
     * Vanilla commands don't care who sends them, and Paper's feedback sender hands their output back line by line.
     * Plugin commands get the real console: LuckPerms and every console-only command check for ConsoleCommandSender
     * (on Folia LuckPerms throws for anything else). Paper turns every ConsoleCommandSender into the server console,
     * so their replies are read from the console log.
     */
    static boolean isVanilla(String label) {
        CommandMap map = Bukkit.getCommandMap();
        Command command = map.getCommand(label);
        if (command == null) return false;
        // Each label gets its own wrapper object, so compare the kind: Paper wraps a vanilla command in
        // VanillaCommandWrapper (plugins' Brigadier commands use a subclass, which must not count), and a plugin
        // overriding the name puts its own command under the bare label.
        String bare = label.substring(label.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        return command.getClass().getName().equals(VANILLA_WRAPPER) && map.getKnownCommands().containsKey("minecraft:" + bare);
    }

    static Player online(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new AgentError("PLAYER_NOT_FOUND", "No online player named " + name,
                    "Use world_query {action:'players'} to see who is online.");
        }
        return player;
    }

    private static JsonObject dispatch(String command, Optional<String> asPlayer, List<String> output,
                                       LogCapture logs, AtomicReference<AutoCloseable> listening) {
        String label = command.split(" ", 2)[0];
        CommandSender sender;
        if (asPlayer.isPresent()) {
            sender = online(asPlayer.get());
        } else if (isVanilla(label)) {
            sender = Bukkit.createCommandSender(c -> output.add(PlainTextComponentSerializer.plainText().serialize(c)));
        } else {
            sender = Bukkit.getConsoleSender();
            listening.set(logs.listen(e -> {
                if (CONSOLE_LOGGERS.contains(e.data().get("logger").getAsString())) {
                    String line = ANSI.matcher(e.data().get("message").getAsString()).replaceAll("");
                    output.add(line.startsWith(SYSTEM_CHAT) ? line.substring(SYSTEM_CHAT.length()) : line);
                }
            }));
        }
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
