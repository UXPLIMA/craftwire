package com.uxplima.craftwire.botrunner.javabot.bot;

import com.uxplima.craftwire.bot.inbox.Inbox;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.mc.ChatClicks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * The bot's received messages: chat, system lines and the action bar into the inbox, in the plugin bot's shape (the
 * message content as text, the chat type's sender name), plus the click events of recent clickable messages so
 * chat_click can run them with their payloads and dialogs. Runner loop thread.
 */
final class JavaChat {
    private static final int CLICK_MESSAGES = 64;

    private final Inbox inbox = new Inbox(200);
    private final Map<Long, List<ClickEvent>> clicks = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<ClickEvent>> eldest) {
            return size() > CLICK_MESSAGES;
        }
    };

    Inbox inbox() {
        return inbox;
    }

    void message(String kind, Component c, String sender, long now) {
        List<ChatClicks.Part> parts = ChatClicks.parts(c);
        Message m = inbox.add(kind, c.getString(), sender, parts.stream().map(ChatClicks.Part::action).toList(), ChatClicks.hover(c), now);
        if (!parts.isEmpty()) clicks.put(m.id(), parts.stream().map(ChatClicks.Part::event).toList());
    }

    /** The click events of a message, or an empty list once it is too old. */
    List<ClickEvent> clicks(long messageId) {
        return clicks.getOrDefault(messageId, List.of());
    }
}
