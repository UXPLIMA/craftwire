package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.Params;
import net.minecraft.client.Minecraft;

final class ChatSendHandler {
    private ChatSendHandler() {}

    static JsonElement send(JsonObject params) {
        String text = Params.optString(params, "text").orElse("").strip();
        boolean command = Params.optBool(params, "command").orElse(false);
        if (text.isEmpty()) throw Params.invalid("text must not be empty");
        if (text.length() > 256) throw Params.invalid("text is longer than 256 characters");
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) throw Params.notInWorld();
        if (command) mc.getConnection().sendCommand(text.startsWith("/") ? text.substring(1) : text);
        else mc.getConnection().sendChat(text);
        JsonObject o = new JsonObject();
        o.addProperty("sent", true);
        return o;
    }
}
