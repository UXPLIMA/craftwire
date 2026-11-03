package com.uxplima.craftwire.bot.gui;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.ItemInfo;

/** A non-empty slot of an open menu: its raw index, whether it belongs to the menu or the player, and the item. */
public record SlotItem(int slot, boolean menu, ItemInfo item) {
    public JsonObject json() {
        JsonObject o = item.json();
        o.addProperty("slot", slot);
        o.addProperty("container", menu ? "menu" : "player");
        return o;
    }
}
