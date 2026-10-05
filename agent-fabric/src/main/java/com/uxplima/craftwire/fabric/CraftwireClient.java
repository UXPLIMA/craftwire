package com.uxplima.craftwire.fabric;

import net.fabricmc.api.ClientModInitializer;

public final class CraftwireClient implements ClientModInitializer {
    private static CraftwireAgent agent;

    @Override
    public void onInitializeClient() {
        agent = new CraftwireAgent();
        agent.start();
    }

    public static CraftwireAgent agent() {
        return agent;
    }
}
