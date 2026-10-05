package com.uxplima.craftwire.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

final class KillSwitch {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("craftwire", "main"));
    private final KeyMapping key = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.craftwire.pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY));
    private final CraftwireAgent agent;

    KillSwitch(CraftwireAgent agent) {
        this.agent = agent;
    }

    void onEndTick(Minecraft mc) {
        while (key.consumeClick()) {
            boolean paused = !agent.dispatcher().isPaused();
            agent.dispatcher().setPaused(paused);
            mc.gui.hud.setOverlayMessage(Component.literal(paused
                    ? "⏸ Craftwire paused — AI control off (F8 to resume)"
                    : "▶ Craftwire resumed — AI control on"), false);
        }
    }
}
