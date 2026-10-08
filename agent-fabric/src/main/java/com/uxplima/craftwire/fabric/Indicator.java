package com.uxplima.craftwire.fabric;

import com.uxplima.craftwire.fabric.video.Recorder;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class Indicator {
    private Indicator() {}

    static void register(CraftwireAgent agent) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("craftwire", "indicator"), (graphics, delta) -> {
            if (!agent.isConnected() || agent.isCaptureInProgress() || Recorder.INSTANCE.isActive()) return;
            Minecraft mc = Minecraft.getInstance();
            boolean paused = agent.dispatcher().isPaused();
            graphics.text(mc.font, paused ? "⏸ Craftwire paused (F8)" : "⚡ Craftwire connected", 4, 4,
                    paused ? 0xFFFFAA00 : 0xFF55FF55, true);
        });
    }
}
