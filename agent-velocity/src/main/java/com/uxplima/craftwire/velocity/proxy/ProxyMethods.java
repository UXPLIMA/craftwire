package com.uxplima.craftwire.velocity.proxy;

import com.uxplima.craftwire.core.Dispatcher;
import com.velocitypowered.api.proxy.ProxyServer;

/** Registers the proxy agent's methods (protocol/PROTOCOL.md, "Proxy methods"). */
public final class ProxyMethods {
    private ProxyMethods() {}

    public static void register(Dispatcher d, ProxyServer proxy) {
        ProxyServers servers = new ProxyServers(proxy);
        ProxyPlayers players = new ProxyPlayers(proxy);
        ProxyCommand command = new ProxyCommand(proxy);
        d.register("proxy.info", servers::info);
        d.register("proxy.servers", servers::servers);
        d.register("proxy.players", players::players);
        d.register("proxy.send", players::send);
        d.register("proxy.kick", players::kick);
        d.register("proxy.command", command::run);
    }
}
