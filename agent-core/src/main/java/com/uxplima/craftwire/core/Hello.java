package com.uxplima.craftwire.core;

/** First message an agent sends. {@code serverDir} and {@code pid} are set by server agents only. */
public record Hello(String agentKind, String agentVersion, String mcVersion, String instanceName, String serverDir, Long pid) {
    public Hello(String agentKind, String agentVersion, String mcVersion, String instanceName) {
        this(agentKind, agentVersion, mcVersion, instanceName, null, null);
    }
}
