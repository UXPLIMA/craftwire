package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Runs a chat click event exactly as clicking it in the chat screen does (commands, dialogs, custom payloads). */
@Mixin(Screen.class)
public interface ScreenInvoker {
    @Invoker("defaultHandleGameClickEvent")
    static void craftwire$handleGameClickEvent(ClickEvent event, Minecraft mc, Screen screen) {
        throw new AssertionError("mixin invoker");
    }
}
