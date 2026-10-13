package com.uxplima.craftwire.paper;

import com.uxplima.craftwire.core.AgentError;
import org.bukkit.configuration.ConfigurationSection;

public record AgentConfig(String instanceName, boolean allowEval, boolean allowWorldEdit, boolean allowBots, long maxEditVolume, boolean recordEvents,
                          boolean allowBuild, long maxBuildVolume, long buildJournalLimitMb) {
    public static AgentConfig from(ConfigurationSection c, String fallbackName) {
        String name = c.getString("instance-name", "");
        return new AgentConfig(name == null || name.isBlank() ? fallbackName : name,
                c.getBoolean("allow-eval", true),
                c.getBoolean("allow-world-edit", true),
                c.getBoolean("allow-bots", true),
                Math.max(1, c.getLong("max-edit-volume", 1_000_000)),
                c.getBoolean("record-events", true),
                c.getBoolean("allow-build", true),
                Math.max(1, c.getLong("max-build-volume", 5_000_000)),
                Math.max(1, c.getLong("build-journal-limit-mb", 512)));
    }

    public void require(boolean allowed, String key) {
        if (!allowed) {
            throw new AgentError("PERMISSION_DISABLED", key + " is disabled in plugins/Craftwire/config.yml",
                    "Ask the server owner to set " + key + ": true and restart the server.");
        }
    }
}
