package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.bot.json.Params;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;

/**
 * bot_action command: sends the command the way a client does (the chat-command packet), so
 * PlayerCommandPreprocessEvent fires and command blockers, aliases and loggers see bot commands; then collects
 * what the bot was told. success = the command exists and no plugin cancelled it.
 */
final class BotCommands {
    private record Ran(Bot bot, boolean known, CommandWatch watch) {}

    private BotCommands() {}

    static CompletableFuture<JsonElement> run(JsonObject p, BotManager bots, String name) {
        String raw = Params.string(p, "command").strip();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        long collectMs = Params.clampedLong(p, "collectMs", 300L, 0L, 5000L);
        long started = System.currentTimeMillis();
        return bots.on(name, b -> {
                    String label = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
                    boolean known = Bukkit.getCommandMap().getCommand(label) != null;
                    CommandWatch watch = CommandWatch.start(bots.plugin(), b.uuid());
                    b.command(command);
                    return new Ran(b, known, watch);
                })
                .thenCompose(r -> CompletableFuture.supplyAsync(() -> r,
                        CompletableFuture.delayedExecutor(Math.max(collectMs, 50L), TimeUnit.MILLISECONDS)))
                .thenCompose(r -> bots.on(name, b -> {
                    r.watch().stop();
                    Boolean cancelled = r.watch().cancelled();
                    JsonObject o = new JsonObject();
                    o.addProperty("command", command);
                    o.addProperty("success", r.known() && Boolean.FALSE.equals(cancelled));
                    if (Boolean.TRUE.equals(cancelled)) o.addProperty("cancelled", true);
                    if (!r.known()) o.addProperty("unknown", true);
                    String finalText = r.watch().message();
                    if (finalText != null && !finalText.equals("/" + command)) o.addProperty("rewrittenTo", finalText);
                    o.add("messages", Message.json(r.bot().inbox().since(started, 200)));
                    return (JsonElement) o;
                }).whenComplete((o, t) -> r.watch().stop()));
    }
}
