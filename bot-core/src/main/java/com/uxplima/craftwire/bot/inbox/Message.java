package com.uxplima.craftwire.bot.inbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * One message a bot received.
 *
 * @param id      a per-bot sequence number, so chat_click can name the message
 * @param time    when it arrived (epoch ms)
 * @param kind    chat, system or actionbar
 * @param sender  the chat type's sender name for player chat, else null
 * @param actions the clickable parts, in reading order
 * @param hover   the hover text of the whole message (or its first hovered part), or null
 */
public record Message(long id, long time, String kind, String text, String sender, List<ChatAction> actions, String hover) {
    public Message {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("time", time);
        o.addProperty("kind", kind);
        o.addProperty("text", text);
        if (sender != null) o.addProperty("sender", sender);
        if (!actions.isEmpty()) {
            JsonArray a = new JsonArray();
            actions.forEach(c -> a.add(c.json()));
            o.add("clicks", a);
        }
        if (hover != null) o.addProperty("hover", hover);
        return o;
    }

    public static JsonArray json(List<Message> list) {
        JsonArray a = new JsonArray();
        for (Message m : list) a.add(m.json());
        return a;
    }
}
