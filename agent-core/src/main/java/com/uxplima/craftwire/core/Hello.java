package com.uxplima.craftwire.core;

/**
 * First message an agent sends. {@code serverDir} and {@code pid} are set by server agents, {@code gameDir} by client
 * agents (the hub maps clients it started to their agent by it).
 */
public record Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid, String gameDir) {
    public Hello(String agentKind, String agentVersion, String mcVersion, String instanceName) {
        this(agentKind, agentVersion, mcVersion, instanceName, null, null, null);
    }

    public Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid) {
        this(agentKind, agentVersion, mcVersion, instanceName, serverDir, pid, null);
    }
}
