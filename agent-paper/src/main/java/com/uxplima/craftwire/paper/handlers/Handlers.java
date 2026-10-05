package com.uxplima.craftwire.paper.handlers;

import com.uxplima.craftwire.core.Dispatcher;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.Sync;

public final class Handlers {
    private Handlers() {}

    public static void registerAll(CraftwirePlugin plugin) {
        Dispatcher d = plugin.dispatcher();
        Sync s = plugin.sync();
        d.register("server.info", p -> s.global(ServerInfoHandler::read));
        d.register("server.command", p -> CommandHandler.handle(p, s));
        d.register("server.eval", p -> EvalHandler.handle(p, plugin));
        d.register("world.query", p -> WorldQueryHandler.handle(p, s));
        d.register("world.edit", p -> WorldEditHandler.handle(p, plugin));
        d.register("plugin.manage", p -> PluginManageHandler.handle(p, plugin));
    }
}
