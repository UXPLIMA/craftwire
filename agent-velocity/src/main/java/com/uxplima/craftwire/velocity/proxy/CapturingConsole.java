package com.uxplima.craftwire.velocity.proxy;

import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.kyori.adventure.audience.MessageType;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;

/**
 * A console for one proxy command: it has every permission and counts as the console (plugins check for
 * ConsoleCommandSource before running console-only commands), and it keeps what the command replies instead of
 * printing it. Replies may come from any thread, so the lines are a concurrent list.
 */
final class CapturingConsole implements ConsoleCommandSource {
    private final List<String> lines = new CopyOnWriteArrayList<>();

    List<String> lines() {
        return List.copyOf(lines);
    }

    @Override
    public Tristate getPermissionValue(String permission) {
        return Tristate.TRUE;
    }

    @Override
    public void sendMessage(Component message) {
        lines.add(ProxyPlayers.plain(message));
    }

    // Adventure's older entry point: callers that pass an identity or message type end up here, not above.
    @Override
    @SuppressWarnings("deprecation")
    public void sendMessage(Identity source, Component message, MessageType type) {
        lines.add(ProxyPlayers.plain(message));
    }
}
