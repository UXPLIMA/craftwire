package com.uxplima.craftwire.paper.bot;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BotNamesTest {
    @Test
    void allocatesNumberedNamesSkippingTakenOnes() {
        assertEquals(List.of("Bot1", "Bot3", "Bot4"), BotNames.allocate("Bot", 3, Set.of("Bot2")::contains));
    }

    @Test
    void skipsNamesShorterThanThree() {
        assertEquals(List.of("B10"), BotNames.allocate("B", 1, n -> false));
    }

    @Test
    void rejectsBadNamesAndPrefixes() {
        assertEquals("Tester_2", BotNames.validate("Tester_2"));
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> BotNames.validate("ab")).code());
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> BotNames.validate("no spaces")).code());
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> BotNames.allocate("ThisPrefixIsTooLong", 1, n -> false)).code());
    }

    @Test
    void failsWhenNumbersNoLongerFit() {
        // 13-char prefix leaves room for numbers up to 999.
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> BotNames.allocate("Abcdefghijklm", 1, n -> true)).code());
    }
}
