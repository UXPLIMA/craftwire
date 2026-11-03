package com.uxplima.craftwire.bot.inbox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Messages a bot received (chat, system lines, action bar), newest last. Thread-safe: network threads write while the
 * bot's own thread reads.
 */
public final class Inbox {
    private final Deque<Message> items = new ArrayDeque<>();
    private final int capacity;
    private long nextId = 1;

    public Inbox(int capacity) {
        this.capacity = capacity;
    }

    public void add(String kind, String text, String sender, long time) {
        add(kind, text, sender, List.of(), null, time);
    }

    public synchronized Message add(String kind, String text, String sender, List<ChatAction> actions, String hover, long time) {
        Message m = new Message(nextId++, time, kind, text, sender, actions, hover);
        items.addLast(m);
        while (items.size() > capacity) items.removeFirst();
        return m;
    }

    /** Messages at or after `time`, at most the newest `limit`. */
    public synchronized List<Message> since(long time, int limit) {
        List<Message> out = new ArrayList<>();
        for (Message m : items) if (m.time() >= time) out.add(m);
        return List.copyOf(out.subList(Math.max(0, out.size() - limit), out.size()));
    }

    /** Messages with an id above `id`, oldest first: what a behavior has not seen yet. */
    public synchronized List<Message> after(long id) {
        List<Message> out = new ArrayList<>();
        for (Message m : items) if (m.id() > id) out.add(m);
        return out;
    }

    public synchronized Optional<Message> byId(long id) {
        for (Message m : items) if (m.id() == id) return Optional.of(m);
        return Optional.empty();
    }

    /** The newest message that has a clickable part whose text or whole text contains `match` (case-insensitive). */
    public synchronized Optional<Message> newestClickable(String match) {
        String q = match.toLowerCase(Locale.ROOT);
        Iterator<Message> it = items.descendingIterator();
        while (it.hasNext()) {
            Message m = it.next();
            if (m.actions().isEmpty()) continue;
            if (m.text().toLowerCase(Locale.ROOT).contains(q)) return Optional.of(m);
        }
        return Optional.empty();
    }

    public synchronized long lastId() {
        return nextId - 1;
    }
}
