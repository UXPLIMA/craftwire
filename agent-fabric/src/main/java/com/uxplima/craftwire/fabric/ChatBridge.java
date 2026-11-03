package com.uxplima.craftwire.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.inbox.ChatAction;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.fabric.gui.ChatLog;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

/** Forwards received chat to the hub as `chat` and `hud` events, with message ids and clickable parts for chat_click. */
final class ChatBridge {
    private ChatBridge() {}

    static void register(CraftwireAgent agent) {
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, bound, time) ->
                agent.emit("chat", event("chat", message, sender == null ? null : sender.name())));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                JsonObject d = new JsonObject();
                d.addProperty("text", message.getString());
                d.addProperty("element", "actionbar");
                agent.emit("hud", d);
            } else {
                agent.emit("chat", event("game", message, null));
            }
        });
    }

    private static JsonObject event(String kind, Component message, String sender) {
        Message m = ChatLog.INSTANCE.add(kind, message, sender);
        JsonObject d = new JsonObject();
        d.addProperty("id", m.id());
        d.addProperty("text", m.text());
        d.addProperty("kind", kind);
        if (sender != null) d.addProperty("sender", sender);
        if (!m.actions().isEmpty()) {
            JsonArray a = new JsonArray();
            for (ChatAction c : m.actions()) a.add(c.json());
            d.add("actions", a);
        }
        if (m.hover() != null) d.addProperty("hover", m.hover());
        return d;
    }
}
