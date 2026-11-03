package com.uxplima.craftwire.velocity;

import com.uxplima.craftwire.velocity.proxy.ProxyEvents;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;

/**
 * The Craftwire Velocity plugin: connects the proxy to the hub as agent kind "proxy" when the proxy starts and
 * reports joins, server switches, kicks and leaves. Velocity runs event handlers on its async event threads; nothing
 * here needs a particular thread, since the proxy API it uses is thread-safe.
 */
public final class CraftwireVelocity {
    private final ProxyServer proxy;
    private VelocityAgent agent;

    @Inject
    public CraftwireVelocity(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        agent = new VelocityAgent(proxy, PluginVersion.VERSION);
        proxy.getEventManager().register(this, new ProxyEvents(agent::notifyEvent));
        agent.start();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (agent != null) agent.close();
    }
}
