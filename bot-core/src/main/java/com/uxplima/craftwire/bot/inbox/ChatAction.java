package com.uxplima.craftwire.bot.inbox;

import com.google.gson.JsonObject;

/**
 * A clickable part of a chat message: the text the player clicks and its click event.
 *
 * @param text   the visible text of the clickable part
 * @param action run_command, suggest_command, open_url, copy_to_clipboard, change_page, show_dialog or custom
 * @param value  the command, URL, text, page or payload id of the event
 * @param hover  the hover text of that part, or null
 */
public record ChatAction(String text, String action, String value, String hover) {
    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        o.addProperty("action", action);
        o.addProperty("value", value);
        if (hover != null) o.addProperty("hover", hover);
        return o;
    }
}
