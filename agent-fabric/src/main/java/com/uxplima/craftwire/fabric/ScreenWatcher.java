package com.uxplima.craftwire.fabric;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Emits `screen` events when the open screen changes (checked each client tick). */
final class ScreenWatcher {
    private final CraftwireAgent agent;
    private Screen last;

    ScreenWatcher(CraftwireAgent agent) {
        this.agent = agent;
    }

    void onEndTick(Minecraft mc) {
        Screen now = mc.gui.screen();
        if (now == last) return;
        if (now == null) agent.emit("screen", describe(last, false));
        else agent.emit("screen", describe(now, true));
        last = now;
    }

    private static JsonObject describe(Screen s, boolean open) {
        JsonObject d = new JsonObject();
        d.addProperty("open", open);
        d.addProperty("title", s == null ? "" : s.getTitle().getString());
        d.addProperty("type", s == null ? "" : s.getClass().getSimpleName());
        return d;
    }
}
