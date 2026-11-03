package com.uxplima.craftwire.bot.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.ItemInfo;
import java.util.List;
import java.util.Optional;

/**
 * A bot's open menu (or its own inventory) as one snapshot, in the shape gui_read returns on every bot kind and on
 * the client. Raw slot indices: the menu's slots first ({@code topSize} of them), then the player's 27 main and 9
 * hotbar slots.
 *
 * @param open      a real menu is open (a chest, a plugin GUI); false for the bot's own inventory
 * @param type      the menu type, e.g. GENERIC_9X3, ANVIL, MERCHANT, or INVENTORY
 * @param carried   the item on the cursor, or null
 * @param screen    screen-specific state (anvil cost, merchant offers, enchanting options, …), or null
 */
public record GuiView(boolean open, String title, String type, int containerId, int stateId, int slotCount, int topSize,
                      List<SlotItem> slots, ItemInfo carried, JsonObject screen) {
    public GuiView {
        slots = List.copyOf(slots);
    }

    /** No menu and the bot's inventory not asked for: what gui_read reports as {open:false}. */
    public static GuiView closed() {
        return new GuiView(false, null, null, -1, 0, 0, 0, List.of(), null, null);
    }

    public boolean present() {
        return slotCount > 0;
    }

    public Optional<ItemInfo> item(int slot) {
        for (SlotItem s : slots) if (s.slot() == slot) return Optional.of(s.item());
        return Optional.empty();
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("open", open);
        if (!present()) {
            // A sign editor or a dialog can be open without any menu.
            if (screen != null) o.add("screen", screen);
            return o;
        }
        if (!open) o.addProperty("inventory", true);
        o.addProperty("title", title);
        o.addProperty("type", type);
        o.addProperty("slotCount", slotCount);
        JsonArray a = new JsonArray();
        for (SlotItem s : slots) a.add(s.json());
        o.add("slots", a);
        o.add("carried", carried == null ? null : carried.json());
        if (screen != null) o.add("screen", screen);
        return o;
    }
}
