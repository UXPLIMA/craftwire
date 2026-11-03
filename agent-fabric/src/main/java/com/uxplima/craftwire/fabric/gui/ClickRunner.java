package com.uxplima.craftwire.fabric.gui;

import com.uxplima.craftwire.bot.gui.GuiStep;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.handlers.GuiActionHandler;
import com.uxplima.craftwire.fabric.mixin.AbstractContainerScreenAccessor;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/**
 * Sends vocabulary clicks through the open screen's own slotClicked, so the screen, the recipe book and the client's
 * prediction react as they do to the mouse. Helper plans run one step per client tick: the server answers each click
 * before the next one is planned on top of it. Render thread.
 */
public final class ClickRunner {
    private ClickRunner() {}

    /** One container click; slot -999 is a click outside the window. */
    public static void click(AbstractContainerScreen<?> screen, RawClick c) {
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        List<Slot> slots = screen.getMenu().slots;
        Slot slot = null;
        if (c.slot() != RawClick.OUTSIDE) {
            slot = slots.get(c.slot());
            GuiActionHandler.moveMouseTo(Minecraft.getInstance(), acc.craftwire$getLeftPos() + slot.x + 8, acc.craftwire$getTopPos() + slot.y + 8);
        }
        acc.craftwire$slotClicked(slot, c.slot(), c.button(), ContainerInput.valueOf(c.mode().name()));
    }

    public static void clicks(AbstractContainerScreen<?> screen, List<RawClick> plan) {
        for (RawClick c : plan) click(screen, c);
    }

    /** Runs a helper plan one step per tick and fails as soon as the screen it was planned on is gone. */
    public static CompletableFuture<Void> run(List<GuiStep> steps, AbstractContainerScreen<?> screen, ClientScheduler s) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (GuiStep step : steps) {
            chain = chain.thenCompose(v -> s.call(() -> {
                step(step, screen);
                return null;
            })).thenCompose(v -> s.delay(1));
        }
        return chain;
    }

    private static void step(GuiStep step, AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != screen) {
            throw new AgentError("SCREEN_CHANGED", "The menu closed or changed while the helper was clicking",
                    "Call gui_read to see what happened (a plugin may close its menu after a click).");
        }
        switch (step) {
            case GuiStep.ClickStep c -> click(screen, c.click());
            case GuiStep.HotbarStep h -> {
                if (mc.player != null) mc.player.getInventory().setSelectedSlot(h.slot());
            }
        }
    }
}
