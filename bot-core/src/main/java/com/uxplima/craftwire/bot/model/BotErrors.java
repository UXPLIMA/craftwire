package com.uxplima.craftwire.bot.model;

import com.uxplima.craftwire.core.AgentError;

/** The bot errors every kind reports the same way. */
public final class BotErrors {
    private BotErrors() {}

    public static AgentError notFound(String name) {
        return new AgentError("BOT_NOT_FOUND", "No bot named " + name,
                "bot_spawn creates bots; bot_list shows the bots of every kind.");
    }

    public static AgentError unsupported(BotKind kind, String action) {
        return new AgentError("UNSUPPORTED_FOR_KIND", action + " is not available for " + kind.id() + " bots",
                "Use a bot of another kind (bot_spawn kind:...), or an action that kind supports (see bot_action's description).");
    }

    public static AgentError noScreen(String name) {
        return new AgentError("NO_SCREEN_OPEN", name + " has no menu open",
                "Open one first, e.g. bot_action {action:'command'} with the plugin's menu command, "
                        + "or pass inventory:true for the bot's own inventory.");
    }

    public static AgentError slotOutOfRange(int slot, int count) {
        return new AgentError("SLOT_OUT_OF_RANGE", "Slot " + slot + " is outside 0.." + (count - 1), "Use a slot number from gui_read.");
    }

    public static AgentError invalid(String message) {
        return new AgentError("INVALID_PARAMS", message, "Check the tool's parameter description and retry.");
    }
}
