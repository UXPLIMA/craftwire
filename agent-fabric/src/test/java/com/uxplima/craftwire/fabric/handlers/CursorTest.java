package com.uxplima.craftwire.fabric.handlers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CursorTest {
    @Test
    void theRealCursorMovesOnlyInAWindowTheUserSeesAndUses() {
        assertTrue(GuiActionHandler.movesRealCursor(true, true));
        assertFalse(GuiActionHandler.movesRealCursor(true, false), "the user is in another window");
        assertFalse(GuiActionHandler.movesRealCursor(false, true), "a hidden game (client_process, game tests) can still report focus");
        assertFalse(GuiActionHandler.movesRealCursor(false, false));
    }
}
