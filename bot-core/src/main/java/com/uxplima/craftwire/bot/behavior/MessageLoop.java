package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Sends chat lines (type chat) or commands (type command_loop) on a cadence, in order or at random. `{bot}` and
 * `{n}` in a line become the bot's name and a running count, so many bots do not send identical text.
 */
final class MessageLoop implements Behavior {
    private final boolean commands;
    private final List<String> lines;
    private final boolean shuffle;
    private final Cadence cadence;
    private final SplittableRandom random;
    private final int limit;
    private int sent;

    private MessageLoop(boolean commands, List<String> lines, boolean shuffle, Cadence cadence, SplittableRandom random, int limit) {
        this.commands = commands;
        this.lines = lines;
        this.shuffle = shuffle;
        this.cadence = cadence;
        this.random = random;
        this.limit = limit;
    }

    /** messages | commands [..] (1-100), intervalMs (5000), jitterMs, random (false), limit (0 = no end). */
    static MessageLoop parse(JsonObject p, boolean commands, SplittableRandom random) {
        List<String> lines = Params.strings(p, commands ? "commands" : "messages");
        if (lines.isEmpty() || lines.size() > 100) throw BotErrors.invalid((commands ? "commands" : "messages") + " takes 1-100 entries");
        for (String l : lines) if (l.isBlank() || l.length() > 256) throw BotErrors.invalid("each entry is 1-256 characters");
        return new MessageLoop(commands, List.copyOf(lines), Params.bool(p, "random", false), Cadence.parse(p, 5000, random), random,
                Params.clampedInt(p, "limit", 0, 0, 1_000_000));
    }

    @Override
    public String type() {
        return commands ? "command_loop" : "chat";
    }

    @Override
    public void tick(BotControl bot, long now) {
        if ((limit > 0 && sent >= limit) || !bot.alive() || !cadence.due(now)) return;
        String line = shuffle ? lines.get(random.nextInt(lines.size())) : lines.get(sent % lines.size());
        line = line.replace("{bot}", bot.name()).replace("{n}", Integer.toString(sent + 1));
        sent++;
        if (commands) bot.command(line.startsWith("/") ? line.substring(1) : line);
        else bot.chat(line);
    }

    @Override
    public JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type());
        o.addProperty("entries", lines.size());
        o.addProperty("intervalMs", cadence.intervalMs());
        o.addProperty("sent", sent);
        return o;
    }
}
