package com.uxplima.craftwire.bot.inbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class InboxTest {
    @Test
    void keepsTheNewestMessagesAndFiltersByTime() {
        Inbox inbox = new Inbox(3);
        inbox.add("system", "a", null, 1);
        inbox.add("system", "b", null, 2);
        inbox.add("chat", "c", "Steve", 3);
        inbox.add("actionbar", "d", null, 4);
        assertEquals(List.of("b", "c", "d"), inbox.since(0, 50).stream().map(Message::text).toList());
        assertEquals(List.of("c", "d"), inbox.since(3, 50).stream().map(Message::text).toList());
        assertEquals(List.of("d"), inbox.since(0, 1).stream().map(Message::text).toList());
        assertEquals("Steve", inbox.since(3, 50).getFirst().sender());
    }

    @Test
    void findsClickableMessagesById() {
        Inbox inbox = new Inbox(10);
        inbox.add("system", "plain", null, 1);
        Message m = inbox.add("system", "[Accept] the duel", null, List.of(new ChatAction("[Accept]", "run_command", "/duel accept", null)), null, 2);
        assertEquals(m, inbox.newestClickable("accept").orElseThrow());
        assertEquals(m, inbox.byId(m.id()).orElseThrow());
        assertEquals(List.of(m), inbox.after(1));
    }
}
