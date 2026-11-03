package com.uxplima.craftwire.core;

import java.util.List;

/**
 * First message an agent sends. {@code serverDir} and {@code pid} are set by server agents, {@code gameDir} by client
 * agents (the hub maps clients it started to their agent by it), {@code botKinds} and {@code maxBots} by bot runners
 * (the hub spreads protocol-bot spawns over runners by them).
 */
public record Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid, String gameDir,
                    List<String> botKinds, Integer maxBots) {
    public Hello {
        botKinds = botKinds == null ? null : List.copyOf(botKinds);
    }

    public Hello(String agentKind, String agentVersion, String mcVersion, String instanceName) {
        this(agentKind, agentVersion, mcVersion, instanceName, null, null, null, null, null);
    }

    public Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid) {
        this(agentKind, agentVersion, mcVersion, instanceName, serverDir, pid, null, null, null);
    }

    public Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid, String gameDir) {
        this(agentKind, agentVersion, mcVersion, instanceName, serverDir, pid, gameDir, null, null);
    }
}
