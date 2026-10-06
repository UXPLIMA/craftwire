package com.uxplima.craftwire.paper.bot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class BotInboxTest {
    @Test
    void keepsTheNewestMessagesAndFiltersByTime() {
        BotInbox inbox = new BotInbox(3);
        inbox.add("system", "a", null, 1);
        inbox.add("system", "b", null, 2);
        inbox.add("chat", "c", "Steve", 3);
        inbox.add("actionbar", "d", null, 4);
        assertEquals(List.of("b", "c", "d"), inbox.since(0, 50).stream().map(BotInbox.Message::text).toList());
        assertEquals(List.of("c", "d"), inbox.since(3, 50).stream().map(BotInbox.Message::text).toList());
        assertEquals(List.of("d"), inbox.since(0, 1).stream().map(BotInbox.Message::text).toList());
        assertEquals("Steve", inbox.since(3, 50).getFirst().sender());
    }
}
