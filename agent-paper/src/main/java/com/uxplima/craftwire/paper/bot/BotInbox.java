package com.uxplima.craftwire.paper.bot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Messages a bot received (chat, system lines, action bar), newest last. Thread-safe: Netty may write off-thread. */
public final class BotInbox {
    public record Message(long time, String kind, String text, String sender) {}

    private final Deque<Message> items = new ArrayDeque<>();
    private final int capacity;

    public BotInbox(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(String kind, String text, String sender, long time) {
        items.addLast(new Message(time, kind, text, sender));
        while (items.size() > capacity) items.removeFirst();
    }

    /** Messages at or after `time`, at most the newest `limit`. */
    public synchronized List<Message> since(long time, int limit) {
        List<Message> out = new ArrayList<>();
        for (Message m : items) if (m.time() >= time) out.add(m);
        return List.copyOf(out.subList(Math.max(0, out.size() - limit), out.size()));
    }
}
