package com.uxplima.craftwire.bot.gui;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.List;
import java.util.Set;

/**
 * One click from the vocabulary, as the agent asked for it.
 *
 * @param slot   the menu slot (raw index from gui_read); -1 for drag and outside
 * @param key    the number key 1-9 for {@link ClickType#NUMBER}, else 0
 * @param button left, right or middle for drag and outside, else left
 * @param slots  the swept slots for drag, else empty
 */
public record Click(ClickType type, int slot, int key, String button, List<Integer> slots) {
    private static final Set<String> BUTTONS = Set.of("left", "right", "middle");

    public Click {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    public static Click of(ClickType type, int slot) {
        return new Click(type, slot, 0, "left", List.of());
    }

    /** `click` (default left), `slot`, `key` (number), `button` (drag, outside) and `slots` (drag). */
    public static Click parse(JsonObject p) {
        ClickType type = ClickType.parse(Params.optString(p, "click").orElse("left"));
        int slot = type.needsSlot() ? Params.integer(p, "slot") : -1;
        int key = 0;
        if (type == ClickType.NUMBER) {
            key = Params.integer(p, "key");
            if (key < 1 || key > 9) throw BotErrors.invalid("key must be 1-9 (the hotbar number key)");
        }
        String button = Params.optString(p, "button").orElse("left");
        if (!BUTTONS.contains(button)) throw BotErrors.invalid("button must be left, right or middle");
        if (type == ClickType.OUTSIDE && button.equals("middle")) throw BotErrors.invalid("outside takes button left or right");
        List<Integer> slots = List.of();
        if (type == ClickType.DRAG) {
            slots = Params.ints(p, "slots");
            if (slots.isEmpty()) throw BotErrors.invalid("drag needs `slots`: the slots to sweep over, in order");
            if (slots.size() != slots.stream().distinct().count()) throw BotErrors.invalid("drag visits each slot once");
        }
        return new Click(type, slot, key, button, slots);
    }

    /** Fails with SLOT_OUT_OF_RANGE when a named slot is not in a menu of `slotCount` slots. */
    public void check(int slotCount) {
        if (type.needsSlot() && (slot < 0 || slot >= slotCount)) throw BotErrors.slotOutOfRange(slot, slotCount);
        for (int s : slots) if (s < 0 || s >= slotCount) throw BotErrors.slotOutOfRange(s, slotCount);
    }
}
