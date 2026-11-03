package com.uxplima.craftwire.bot.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.SlotItem;
import com.uxplima.craftwire.bot.json.Jsons;
import java.util.List;

/**
 * A bot's state in the one shape every kind reports (bot_action state), so a scenario written against plugin bots
 * reads the same fields from Java and Bedrock bots.
 *
 * @param inventory the player inventory by inventory index (0-8 hotbar, 9-35 main, 36-39 armour, 40 off hand)
 * @param openMenu  the open menu's title, or null
 */
public record BotState(String name, BotKind kind, String uuid, String group, String world, Position pos, float yaw, float pitch,
                       double health, int food, String gameMode, boolean onGround, boolean dead, boolean moving, boolean digging,
                       boolean sneaking, boolean sprinting, int heldSlot, ItemInfo held, List<SlotItem> inventory, String openMenu) {
    public BotState {
        inventory = inventory == null ? List.of() : List.copyOf(inventory);
    }

    /** name, kind, uuid, group, world and position: what spawn and list report per bot. */
    public static JsonObject summary(String name, BotKind kind, String uuid, String group, String world, Position pos) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("kind", kind.id());
        o.addProperty("uuid", uuid);
        if (group != null) o.addProperty("group", group);
        o.addProperty("world", world);
        o.addProperty("x", Jsons.round(pos.x()));
        o.addProperty("y", Jsons.round(pos.y()));
        o.addProperty("z", Jsons.round(pos.z()));
        return o;
    }

    public JsonObject json() {
        JsonObject o = summary(name, kind, uuid, group, world, pos);
        o.addProperty("yaw", Jsons.round(yaw));
        o.addProperty("pitch", Jsons.round(pitch));
        o.addProperty("health", health);
        o.addProperty("food", food);
        o.addProperty("gameMode", gameMode);
        o.addProperty("onGround", onGround);
        o.addProperty("dead", dead);
        o.addProperty("moving", moving);
        o.addProperty("digging", digging);
        o.addProperty("sneaking", sneaking);
        o.addProperty("sprinting", sprinting);
        o.addProperty("heldSlot", heldSlot);
        if (held != null) o.add("held", held.json());
        JsonArray inv = new JsonArray();
        for (SlotItem s : inventory) {
            JsonObject it = s.item().json();
            it.addProperty("slot", s.slot());
            inv.add(it);
        }
        o.add("inventory", inv);
        if (openMenu != null) o.addProperty("openMenu", openMenu);
        else o.add("openMenu", JsonNull.INSTANCE);
        return o;
    }
}
