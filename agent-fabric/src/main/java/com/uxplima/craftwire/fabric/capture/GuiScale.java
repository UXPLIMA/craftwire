package com.uxplima.craftwire.fabric.capture;

import com.uxplima.craftwire.fabric.handlers.GuiActionHandler;
import com.uxplima.craftwire.fabric.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Changes the GUI scale for a capture. The open screen is laid out again, so the mouse is moved to stay over what it
 * was over: the hovered slot (its tooltip stays shown), else the same point relative to the screen's centre.
 */
public final class GuiScale {
    private GuiScale() {}

    /** Render thread. */
    public static void apply(Minecraft mc, int scale) {
        if (mc.options.guiScale().get() == scale) return;
        var window = mc.getWindow();
        Screen screen = mc.gui.screen();
        Slot hovered = screen instanceof AbstractContainerScreen<?> acs ? ((AbstractContainerScreenAccessor) acs).craftwire$getHoveredSlot() : null;
        double dx = mc.mouseHandler.getScaledXPos(window) - window.getGuiScaledWidth() / 2.0;
        double dy = mc.mouseHandler.getScaledYPos(window) - window.getGuiScaledHeight() / 2.0;
        mc.options.guiScale().set(scale);
        mc.resizeGui();
        if (screen == null || mc.gui.screen() != screen) return;
        if (hovered != null) {
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
            GuiActionHandler.moveMouseTo(mc, acc.craftwire$getLeftPos() + hovered.x + 8, acc.craftwire$getTopPos() + hovered.y + 8);
        } else {
            GuiActionHandler.moveMouseTo(mc, window.getGuiScaledWidth() / 2.0 + dx, window.getGuiScaledHeight() / 2.0 + dy);
        }
    }
}
