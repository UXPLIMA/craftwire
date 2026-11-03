package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;

/**
 * Runs a click event the way the vanilla client does when a player clicks chat text or a dialog button: commands are
 * sent, custom clicks go to the server with their payload, dialogs open. What only changes the client (a URL, the
 * clipboard, the chat box) is reported and not followed. Bot thread.
 */
final class BotClickEvents {
    private BotClickEvents() {}

    static JsonObject run(Bot b, ClickEvent e) {
        JsonObject r = new JsonObject();
        switch (e) {
            case ClickEvent.RunCommand c -> {
                String command = c.command().startsWith("/") ? c.command().substring(1) : c.command();
                b.command(command);
                r.addProperty("ran", "/" + command);
            }
            case ClickEvent.Custom c -> {
                b.listener().handleCustomClickAction(new ServerboundCustomClickActionPacket(c.id(), c.payload()));
                r.addProperty("custom", c.id().toString());
            }
            case ClickEvent.ShowDialog d -> {
                b.link().screens.dialog(d.dialog());
                r.addProperty("opened", "dialog");
            }
            case ClickEvent.SuggestCommand s -> r.addProperty("suggested", s.command());
            case ClickEvent.OpenUrl u -> {
                r.addProperty("url", u.uri().toString());
                r.addProperty("followed", false);
            }
            case ClickEvent.CopyToClipboard c -> r.addProperty("copied", c.value());
            case ClickEvent.ChangePage p -> r.addProperty("page", p.page());
            default -> r.addProperty("ignored", e.action().getSerializedName());
        }
        return r;
    }
}
