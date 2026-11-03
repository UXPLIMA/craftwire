package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import com.uxplima.craftwire.bot.gui.ScreenAction;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import com.uxplima.craftwire.fabric.gui.ClientScreenActions;
import com.uxplima.craftwire.fabric.gui.SlotActions;
import com.uxplima.craftwire.fabric.mixin.MouseHandlerAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * gui_action: widgets, typing, the slot click vocabulary, the helpers (move_item, transfer, equip) and the special
 * screens of bot-core's ScreenAction. Every answer is the screen as it is two ticks after the action. Render thread.
 */
public final class GuiActionHandler {
    private GuiActionHandler() {}

    public static CompletableFuture<JsonElement> act(JsonObject p, ClientScheduler s) {
        String action = Params.optString(p, "action").orElseThrow(() -> Params.invalid("action is required"));
        CompletableFuture<JsonObject> done;
        if (ScreenAction.handles(action)) {
            done = s.call(() -> ClientScreenActions.run(ScreenAction.parse(action, p)));
        } else if (SlotActions.HELPERS.contains(action)) {
            done = SlotActions.helper(action, p, s);
        } else {
            done = s.call(() -> {
                perform(action, p);
                return null;
            });
        }
        return done.thenCompose(r -> s.delay(2).thenApply(v -> r)).thenCompose(r -> s.call(() -> {
            JsonObject o = GuiReadHandler.read();
            if (r != null) o.add("result", r);
            return (JsonElement) o;
        }));
    }

    private static void perform(String action, JsonObject p) {
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
            default -> {
                if (!SlotActions.CLICKS.contains(action)) throw Params.invalid("unknown action: " + action);
                SlotActions.click(screen, action, p);
            }
        }
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

    /** Moves the logical cursor to GUI-scaled coordinates, and the real one too when {@link #movesRealCursor} says so. */
    public static void moveMouseTo(Minecraft mc, double guiX, double guiY) {
        Window window = mc.getWindow();
        double sx = guiX * window.getScreenWidth() / (double) window.getGuiScaledWidth();
        double sy = guiY * window.getScreenHeight() / (double) window.getGuiScaledHeight();
        ClientCompat compat = ClientCompat.get();
        if (movesRealCursor(compat.windowVisible(window), window.isFocused())) compat.warpCursor(window, sx, sy);
        MouseHandlerAccessor mouse = (MouseHandlerAccessor) mc.mouseHandler;
        mouse.craftwire$setXpos(sx);
        mouse.craftwire$setYpos(sy);
    }

    /**
     * Whether the system cursor follows: only in a window the user sees and is using. A hidden game can still
     * report focus on Windows, and moving the cursor there would take the user's mouse away from their own work.
     */
    static boolean movesRealCursor(boolean windowVisible, boolean windowFocused) {
        return windowVisible && windowFocused;
    }
}
