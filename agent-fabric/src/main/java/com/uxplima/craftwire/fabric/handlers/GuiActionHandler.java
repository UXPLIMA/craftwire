package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import com.uxplima.craftwire.fabric.mixin.AbstractContainerScreenAccessor;
import com.uxplima.craftwire.fabric.mixin.MouseHandlerAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

public final class GuiActionHandler {
    // Container-click buttons are protocol values (left 0, right 1), not mouse button codes, on every version.
    private static final int CONTAINER_LEFT = 0;
    private static final int CONTAINER_RIGHT = 1;

    private GuiActionHandler() {}

    public static CompletableFuture<JsonElement> act(JsonObject p, ClientScheduler s) {
        return s.call(() -> {
                    perform(p);
                    return Boolean.TRUE;
                })
                .thenCompose(v -> s.delay(2))
                .thenCompose(v -> s.call(() -> (JsonElement) GuiReadHandler.read()));
    }

    private static void perform(JsonObject p) {
        String action = Params.optString(p, "action").orElseThrow(() -> Params.invalid("action is required"));
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        if (screen == null) {
            throw new AgentError("NO_SCREEN_OPEN", "No screen is open.",
                    "Open a menu first (e.g. chat {action:'command', text:'/builders crew'}), then wait_for {condition:'screen_open'}.");
        }
        switch (action) {
            case "close" -> screen.onClose();
            case "click_widget" -> clickWidget(mc, screen, findWidget(screen, p));
            case "type" -> type(mc, screen, p);
            case "hover", "click", "right_click", "shift_click", "drag" -> slotAction(mc, screen, action, p);
            default -> throw Params.invalid("unknown action: " + action);
        }
    }

    private static void slotAction(Minecraft mc, Screen screen, String action, JsonObject p) {
        if (!(screen instanceof AbstractContainerScreen<?> acs)) {
            throw new AgentError("NOT_A_CONTAINER", "The open screen (" + screen.getClass().getSimpleName() + ") has no item slots.",
                    "Use click_widget for buttons; gui_read lists widgets.");
        }
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) acs;
        List<Slot> slots = acs.getMenu().slots;
        int id = slotId(slots, p, "slot");
        Slot slot = slots.get(id);
        moveMouseTo(mc, acc.craftwire$getLeftPos() + slot.x + 8, acc.craftwire$getTopPos() + slot.y + 8);
        switch (action) {
            case "click" -> acc.craftwire$slotClicked(slot, id, CONTAINER_LEFT, ContainerInput.PICKUP);
            case "right_click" -> acc.craftwire$slotClicked(slot, id, CONTAINER_RIGHT, ContainerInput.PICKUP);
            case "shift_click" -> acc.craftwire$slotClicked(slot, id, CONTAINER_LEFT, ContainerInput.QUICK_MOVE);
            case "drag" -> {
                int toId = slotId(slots, p, "toSlot");
                Slot to = slots.get(toId);
                acc.craftwire$slotClicked(slot, id, CONTAINER_LEFT, ContainerInput.PICKUP);
                acc.craftwire$slotClicked(to, toId, CONTAINER_LEFT, ContainerInput.PICKUP);
                moveMouseTo(mc, acc.craftwire$getLeftPos() + to.x + 8, acc.craftwire$getTopPos() + to.y + 8);
            }
            default -> { /* hover: moving the mouse is the whole action */ }
        }
    }

    private static int slotId(List<Slot> slots, JsonObject p, String key) {
        int id = Params.optInt(p, key).orElseThrow(() -> Params.invalid(key + " is required for this action"));
        if (id < 0 || id >= slots.size()) {
            throw new AgentError("SLOT_OUT_OF_RANGE", "Slot " + id + " does not exist; this screen has " + slots.size() + " slots.",
                    "Call gui_read again: the screen may have changed since you last read it.");
        }
        return id;
    }

    private static List<AbstractWidget> widgets(Screen screen) {
        List<AbstractWidget> out = new ArrayList<>();
        for (GuiEventListener child : screen.children()) if (child instanceof AbstractWidget w) out.add(w);
        return out;
    }

    private static AbstractWidget findWidget(Screen screen, JsonObject p) {
        String key = Params.optString(p, "widget").orElseThrow(() -> Params.invalid("widget is required (index or text)"));
        List<AbstractWidget> all = widgets(screen);
        if (key.matches("\\d+")) {
            int i = Integer.parseInt(key);
            if (i < all.size()) return all.get(i);
        } else {
            String needle = key.toLowerCase(Locale.ROOT);
            for (AbstractWidget w : all) if (w.getMessage().getString().toLowerCase(Locale.ROOT).contains(needle)) return w;
        }
        List<String> names = all.stream().map(w -> w.getMessage().getString()).filter(t -> !t.isBlank()).toList();
        throw new AgentError("WIDGET_NOT_FOUND", "No widget matches \"" + key + "\".", "Available widgets: " + names);
    }

    private static void clickWidget(Minecraft mc, Screen screen, AbstractWidget w) {
        double cx = w.getX() + w.getWidth() / 2.0;
        double cy = w.getY() + w.getHeight() / 2.0;
        moveMouseTo(mc, cx, cy);
        MouseButtonEvent event = new MouseButtonEvent(cx, cy, new MouseButtonInfo(ClientCompat.get().mouseButtonLeft(), 0));
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }

    private static void type(Minecraft mc, Screen screen, JsonObject p) {
        String text = Params.optString(p, "text").orElseThrow(() -> Params.invalid("text is required for type"));
        if (p.has("widget")) clickWidget(mc, screen, findWidget(screen, p));
        text.codePoints().forEach(cp -> screen.charTyped(new CharacterEvent(cp)));
    }

    /** Moves the logical (and, when the window has focus, the real) cursor to GUI-scaled coordinates. */
    public static void moveMouseTo(Minecraft mc, double guiX, double guiY) {
        Window window = mc.getWindow();
        double sx = guiX * window.getScreenWidth() / (double) window.getGuiScaledWidth();
        double sy = guiY * window.getScreenHeight() / (double) window.getGuiScaledHeight();
        ClientCompat.get().warpCursor(window, sx, sy);   // only while focused: the user's cursor is never hijacked
        MouseHandlerAccessor mouse = (MouseHandlerAccessor) mc.mouseHandler;
        mouse.craftwire$setXpos(sx);
        mouse.craftwire$setYpos(sy);
    }
}
