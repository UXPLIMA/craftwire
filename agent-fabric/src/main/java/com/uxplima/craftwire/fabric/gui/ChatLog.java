package com.uxplima.craftwire.fabric.gui;

import com.uxplima.craftwire.bot.inbox.Inbox;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.mc.ChatClicks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * The client's recent chat with message ids, and the raw click events of the newest clickable messages, so chat_click
 * runs exactly what the server attached (payloads, inline dialogs) instead of a re-parsed copy. Render thread writes;
 * {@link Inbox} is thread-safe for the hub's reads.
 */
public final class ChatLog {
    public static final ChatLog INSTANCE = new ChatLog();

    private static final int KEEP = 200;
    /** Clickable messages kept with their events; older ones can still be read but no longer clicked. */
    private static final int KEEP_CLICKS = 64;

    private final Inbox inbox = new Inbox(KEEP);
    private final Map<Long, List<ClickEvent>> clicks = new LinkedHashMap<>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, List<ClickEvent>> eldest) {
            return size() > KEEP_CLICKS;
        }
    };

    private ChatLog() {}

    public Message add(String kind, Component c, String sender) {
        List<ChatClicks.Part> parts = ChatClicks.parts(c);
        Message m = inbox.add(kind, c.getString(), sender, parts.stream().map(ChatClicks.Part::action).toList(), ChatClicks.hover(c),
                System.currentTimeMillis());
        if (!parts.isEmpty()) {
            synchronized (clicks) {
                clicks.put(m.id(), parts.stream().map(ChatClicks.Part::event).toList());
            }
        }
        return m;
    }

    public Inbox inbox() {
        return inbox;
    }

    /** The click events of a message, in part order; empty when it had none or is too old. */
    public List<ClickEvent> clicks(long messageId) {
        synchronized (clicks) {
            return clicks.getOrDefault(messageId, List.of());
        }
    }
}
