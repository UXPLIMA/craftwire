package com.uxplima.craftwire.bot.gui;

import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** The click vocabulary shared by every bot kind and the Fabric client: what a player can do to a menu slot. */
public enum ClickType {
    /** Left mouse button: pick up, put down or swap the whole stack. */
    LEFT,
    /** Right mouse button: pick up half, put down one. */
    RIGHT,
    /** Shift + click: move the stack to the other part of the menu. */
    SHIFT,
    /** Number key 1-9 over a slot: swap it with that hotbar slot. */
    NUMBER,
    /** F over a slot: swap it with the off hand. */
    OFFHAND,
    /** Q over a slot: drop one item. */
    DROP,
    /** Ctrl + Q over a slot: drop the whole stack. */
    DROP_STACK,
    /** Middle mouse button: a full stack copy (creative mode only; the server ignores it otherwise). */
    MIDDLE,
    /** Double left click: pick up, then collect every matching stack onto the cursor. */
    DOUBLE,
    /** Hold a button and sweep over several slots: split the cursor stack across them. */
    DRAG,
    /** Click outside the window: drop the cursor stack (left) or one item of it (right). */
    OUTSIDE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ClickType parse(String id) {
        for (ClickType t : values()) if (t.id().equals(id)) return t;
        String all = Arrays.stream(values()).map(ClickType::id).collect(Collectors.joining(", "));
        throw BotErrors.invalid("click must be one of " + all + ", not " + id);
    }

    /** Whether the click names one slot (`slot`); drag names several, outside none. */
    public boolean needsSlot() {
        return this != DRAG && this != OUTSIDE;
    }
}
