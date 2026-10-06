package com.uxplima.craftwire.paper.handlers;

import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.Sync;
import com.uxplima.craftwire.paper.wait.WaitHandler;

public final class Handlers {
    private Handlers() {}

    public static void registerAll(CraftwirePlugin plugin) {
        Dispatcher d = plugin.dispatcher();
        Sync s = plugin.sync();
        d.register("server.info", p -> s.global(ServerInfoHandler::read));
        d.register("server.command", p -> CommandHandler.handle(p, s));
        d.register("server.eval", p -> EvalHandler.handle(p, plugin));
        d.register("world.query", p -> WorldQueryHandler.handle(p, s, plugin.bots()::isBot));
        d.register("world.edit", p -> WorldEditHandler.handle(p, plugin));
        d.register("plugin.manage", p -> PluginManageHandler.handle(p, plugin));
        d.register("events", p -> s.global(() -> EventsHandler.handle(p, plugin)));
        d.register("wait", p -> WaitHandler.handle(p, plugin));
        d.register("bot.spawn", p -> BotHandler.spawn(p, plugin));
        d.register("bot.remove", p -> BotHandler.remove(p, plugin));
        d.register("bot.action", p -> BotHandler.action(p, plugin));
    }
}
