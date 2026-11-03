package com.uxplima.craftwire.script.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.behavior.Behavior;
import com.uxplima.craftwire.bot.behavior.BotControl;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.core.AgentError;

/**
 * One bot's part in a {@link BotScript}: each tick it syncs the bot's handle, hands new messages to onMessage and
 * calls onTick. Runs on the bot's own thread.
 */
public final class ScriptBehavior implements Behavior {
    private final BotScript script;
    private final ScriptBot handle;
    private final String name;
    private long ticks;
    private long lastMessage;

    private ScriptBehavior(BotScript script, String name, long lastMessage) {
        this.script = script;
        this.name = name;
        this.handle = script.attach(name);
        this.lastMessage = lastMessage;
    }

    /** Gives a bot to the script; it sees messages that arrive from now on. */
    public static ScriptBehavior attach(BotScript script, BotControl bot) {
        return new ScriptBehavior(script, bot.name(), bot.inbox().lastId());
    }

    @Override
    public String type() {
        return "script";
    }

    @Override
    public void tick(BotControl bot, long now) {
        String failure = script.failure();
        if (failure != null) throw new AgentError("SCRIPT_FAILED", failure, "Fix the script and set the behavior again.");
        handle.sync(bot);
        for (Message m : bot.inbox().after(lastMessage)) {
            lastMessage = m.id();
            script.message(handle, m);
        }
        script.tick(handle, ticks++);
    }

    @Override
    public void stop(BotControl bot) {
        Behavior.super.stop(bot);
        script.detach(name);
    }

    @Override
    public JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type());
        o.addProperty("ticks", ticks);
        String failure = script.failure();
        if (failure != null) o.addProperty("failure", failure);
        String log = script.takeLog();
        if (!log.isEmpty()) o.addProperty("log", log);
        return o;
    }
}
