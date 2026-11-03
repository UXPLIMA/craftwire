package com.uxplima.craftwire.bot.model;

import com.uxplima.craftwire.core.AgentError;
import java.util.Locale;

/** The three ways Craftwire runs a bot: inside the server, or from the bot runner over the Java or Bedrock protocol. */
public enum BotKind {
    /** A fake player inside a Paper/Folia server (agent-paper); no network connection. */
    PLUGIN,
    /** An external Java Edition client speaking the vanilla protocol (botrunner/java). */
    JAVA,
    /** An external Bedrock Edition client speaking our own Bedrock protocol (botrunner/bedrock). */
    BEDROCK;

    /** The wire name: plugin, java, bedrock. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BotKind parse(String id) {
        for (BotKind k : values()) if (k.id().equals(id)) return k;
        throw new AgentError("INVALID_PARAMS", "kind must be plugin, java or bedrock, not " + id,
                "Use kind:'plugin' for in-server bots, 'java' or 'bedrock' for bots that connect from the bot runner.");
    }
}
