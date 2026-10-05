package com.uxplima.craftwire.fabric.handlers;

import com.uxplima.craftwire.fabric.CraftwireAgent;

public final class Handlers {
    private Handlers() {}

    public static void registerAll(CraftwireAgent agent) {
        var s = agent.scheduler();
        agent.dispatcher().register("player.state", p -> s.call(PlayerStateHandler::read));
        agent.dispatcher().register("chat.send", p -> s.call(() -> ChatSendHandler.send(p)));
        agent.dispatcher().register("hud.read", p -> s.call(HudReadHandler::read));
    }
}
