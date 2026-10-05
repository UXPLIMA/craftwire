package com.uxplima.craftwire.fabric;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

final class ChatBridge {
    private ChatBridge() {}

    static void register(CraftwireAgent agent) {
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, bound, time) -> {
            JsonObject d = new JsonObject();
            d.addProperty("text", message.getString());
            d.addProperty("kind", "chat");
            if (sender != null) d.addProperty("sender", sender.name());
            agent.emit("chat", d);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            JsonObject d = new JsonObject();
            d.addProperty("text", message.getString());
            if (overlay) {
                d.addProperty("element", "actionbar");
                agent.emit("hud", d);
            } else {
                d.addProperty("kind", "game");
                agent.emit("chat", d);
            }
        });
    }
}
