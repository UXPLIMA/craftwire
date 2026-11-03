package com.uxplima.craftwire.bot.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * An item stack as every bot kind reports it: id (namespaced), count, display name, lore lines and the stack limit.
 * {@code maxStack} lets the GUI helpers plan merges without asking the server.
 */
public record ItemInfo(String id, int count, String name, List<String> lore, int maxStack) {
    public ItemInfo {
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    /** Same item and same looks: stacks that merge. */
    public boolean sameItem(ItemInfo o) {
        return o != null && id.equals(o.id) && name.equals(o.name) && lore.equals(o.lore);
    }

    public ItemInfo withCount(int n) {
        return new ItemInfo(id, n, name, lore, maxStack);
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("count", count);
        o.addProperty("name", name);
        if (!lore.isEmpty()) {
            JsonArray a = new JsonArray();
            lore.forEach(a::add);
            o.add("lore", a);
        }
        return o;
    }

    /** Whether `query` (an id with or without namespace, or a display name) names this item; null matches any. */
    public boolean matches(String query) {
        if (query == null || query.isEmpty()) return true;
        String q = query.contains(":") ? query : "minecraft:" + query;
        return id.equals(q) || name.equalsIgnoreCase(query);
    }
}
