package com.uxplima.craftwire.fabric.gui;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.ScreenAction;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.mixin.DialogScreenAccessor;
import com.uxplima.craftwire.fabric.mixin.ScreenInvoker;
import com.uxplima.craftwire.mc.DialogModel;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.dialog.Dialog;

/**
 * Dialog answers and chat clicks on the client. Both end in the client's own click handling, so commands, custom
 * payloads and dialogs behave as for a mouse click; URLs, the clipboard and chat suggestions only change the user's
 * desktop or chat box and are reported, not followed. Render thread.
 */
final class ClientDialogs {
    private ClientDialogs() {}

    /** Picks the button by label or index; `inputs` fill the dialog's inputs by key, the rest keep their initial value. */
    static void answer(Screen screen, ScreenAction.Dialog d, JsonObject r) {
        if (!(screen instanceof DialogScreen<?> ds)) {
            throw new AgentError("NO_DIALOG", "No dialog is open", "Wait for the plugin to show one (wait_for {condition:'screen_open'}).");
        }
        Dialog dialog = ((DialogScreenAccessor) ds).craftwire$getDialog();
        DialogModel.Button button = DialogModel.pick(dialog, d.button());
        r.addProperty("button", button.label());
        Minecraft mc = Minecraft.getInstance();
        if (button.opens() != null) {
            mc.getConnection().showDialog(button.opens(), ds.previousScreen());
            r.addProperty("opened", "dialog");
            return;
        }
        Optional<ClickEvent> e = DialogModel.clickEvent(dialog, button, d.inputs());
        e.ifPresent(ev -> r.add("action", describe(ev)));
        ds.runAction(e, dialog.common().afterAction());
    }

    static void chatClick(Minecraft mc, ScreenAction.ChatClick c, JsonObject r) {
        ChatLog log = ChatLog.INSTANCE;
        Message m = c.messageId() != null
                ? log.inbox().byId(c.messageId()).orElseThrow(() -> new AgentError("MESSAGE_NOT_FOUND", "No message " + c.messageId(),
                        "Use an id from the chat log (craftwire://instances/{name}/chat); only recent messages are kept."))
                : log.inbox().newestClickable(c.match()).orElseThrow(() -> new AgentError("MESSAGE_NOT_FOUND",
                        "No clickable message contains " + c.match(), "Check the chat log for messages with actions."));
        List<ClickEvent> events = log.clicks(m.id());
        if (c.part() >= events.size()) {
            throw BotErrors.invalid("Message " + m.id() + " has " + events.size() + " clickable part(s); part is 0-based");
        }
        ClickEvent e = events.get(c.part());
        r.addProperty("message", m.id());
        r.addProperty("clicked", m.actions().get(c.part()).text());
        r.add("action", describe(e));
        if (followed(e)) ScreenInvoker.craftwire$handleGameClickEvent(e, mc, mc.gui.screen());
    }

    /** Events that act in the game; the rest would open a browser, write the clipboard or fill the chat box. */
    private static boolean followed(ClickEvent e) {
        return e instanceof ClickEvent.RunCommand || e instanceof ClickEvent.Custom || e instanceof ClickEvent.ShowDialog;
    }

    /** The same result shape as the plugin bot's click events. */
    private static JsonObject describe(ClickEvent e) {
        JsonObject r = new JsonObject();
        switch (e) {
            case ClickEvent.RunCommand c -> r.addProperty("ran", c.command().startsWith("/") ? c.command() : "/" + c.command());
            case ClickEvent.Custom c -> r.addProperty("custom", c.id().toString());
            case ClickEvent.ShowDialog d -> r.addProperty("opened", "dialog");
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
