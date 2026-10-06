package com.uxplima.craftwire.fabric.handlers;

import com.uxplima.craftwire.fabric.CraftwireAgent;

public final class Handlers {
    private Handlers() {}

    public static void registerAll(CraftwireAgent agent) {
        var s = agent.scheduler();
        agent.dispatcher().register("player.state", p -> s.call(PlayerStateHandler::read));
        agent.dispatcher().register("chat.send", p -> s.call(() -> ChatSendHandler.send(p)));
        agent.dispatcher().register("hud.read", p -> s.call(HudReadHandler::read));
        agent.dispatcher().register("gui.read", p -> s.call(() -> (com.google.gson.JsonElement) GuiReadHandler.read()));
        agent.dispatcher().register("gui.action", p -> GuiActionHandler.act(p, s));
        agent.dispatcher().register("camera", p -> s.call(() -> CameraHandler.handle(p)));
        agent.dispatcher().register("screenshot", p -> ScreenshotHandler.capture(p, agent));
        agent.dispatcher().register("input", p -> s.call(() -> InputHandler.handle(p, s)));
        agent.dispatcher().register("client.settings", p -> s.call(() -> ClientSettingsHandler.handle(p)));
        agent.dispatcher().register("client.quit", p -> s.call(QuitHandler::quit));
        agent.dispatcher().register("world.open", p -> s.call(() -> WorldHandler.open(p)));
    }
}
