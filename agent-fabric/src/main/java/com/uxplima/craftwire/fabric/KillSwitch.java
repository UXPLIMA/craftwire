package com.uxplima.craftwire.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
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
        // Key mappings are not processed while a screen is open, but menus are where the AI works most.
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) ->
                ScreenKeyboardEvents.beforeKeyPress(screen).register((s, event) -> {
                    if (key.matches(event)) toggle(mc);
                }));
    }

    void onEndTick(Minecraft mc) {
        while (key.consumeClick()) toggle(mc);
    }

    private void toggle(Minecraft mc) {
        boolean paused = !agent.dispatcher().isPaused();
        agent.dispatcher().setPaused(paused);
        mc.gui.hud.setOverlayMessage(Component.literal(paused
                ? "⏸ Craftwire paused — AI control off (F8 to resume)"
                : "▶ Craftwire resumed — AI control on"), false);
    }
}
