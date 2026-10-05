package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.mixin.HudAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

final class ClientSettingsHandler {
    private ClientSettingsHandler() {}

    static JsonElement handle(JsonObject p) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        Params.optInt(p, "guiScale").ifPresent(v -> o.guiScale().set(v));
        Params.optInt(p, "fov").ifPresent(v -> o.fov().set(v));
        Params.optInt(p, "renderDistance").ifPresent(v -> o.renderDistance().set(v));
        Params.optBool(p, "hideHud").ifPresent(v -> ((HudAccessor) mc.gui.hud).craftwire$setHidden(v));
        if (p.has("windowSize") && p.get("windowSize").isJsonObject()) {
            JsonObject ws = p.getAsJsonObject("windowSize");
            mc.getWindow().setWindowed(ws.get("width").getAsInt(), ws.get("height").getAsInt());
        }
        Window w = mc.getWindow();
        JsonObject out = new JsonObject();
        out.addProperty("guiScale", o.guiScale().get());
        out.addProperty("fov", o.fov().get());
        out.addProperty("renderDistance", o.renderDistance().get());
        out.addProperty("hideHud", ((HudAccessor) mc.gui.hud).craftwire$isHidden());
        JsonObject size = new JsonObject();
        size.addProperty("width", w.getScreenWidth());
        size.addProperty("height", w.getScreenHeight());
        out.add("windowSize", size);
        return out;
    }
}
