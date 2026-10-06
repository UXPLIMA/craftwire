package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

public final class BotJson {
    private BotJson() {}

    public static JsonObject summary(Bot b) {
        Location l = b.bukkit().getLocation();
        JsonObject o = new JsonObject();
        o.addProperty("name", b.name());
        o.addProperty("uuid", b.uuid().toString());
        o.addProperty("world", l.getWorld().getName());
        o.addProperty("x", round(l.getX()));
        o.addProperty("y", round(l.getY()));
        o.addProperty("z", round(l.getZ()));
        return o;
    }

    public static JsonObject item(ItemStack s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.getType().getKey().toString());
        o.addProperty("count", s.getAmount());
        o.addProperty("name", plain(s.effectiveName()));
        List<Component> lore = s.lore();
        if (lore != null && !lore.isEmpty()) {
            JsonArray a = new JsonArray();
            lore.forEach(c -> a.add(plain(c)));
            o.add("lore", a);
        }
        return o;
    }

    public static JsonArray messages(List<BotInbox.Message> list) {
        JsonArray a = new JsonArray();
        for (BotInbox.Message m : list) {
            JsonObject o = new JsonObject();
            o.addProperty("time", m.time());
            o.addProperty("kind", m.kind());
            o.addProperty("text", m.text());
            if (m.sender() != null) o.addProperty("sender", m.sender());
            a.add(o);
        }
        return a;
    }

    public static JsonObject state(Bot b) {
        Player p = b.bukkit();
        JsonObject o = summary(b);
        o.addProperty("yaw", round(p.getLocation().getYaw()));
        o.addProperty("pitch", round(p.getLocation().getPitch()));
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFoodLevel());
        o.addProperty("gameMode", p.getGameMode().name().toLowerCase(Locale.ROOT));
        o.addProperty("onGround", p.isOnGround());
        o.addProperty("dead", p.isDead());
        o.addProperty("moving", b.moving());
        o.addProperty("heldSlot", p.getInventory().getHeldItemSlot());
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!held.getType().isAir()) o.add("held", item(held));
        JsonArray inv = new JsonArray();
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null || contents[i].getType().isAir()) continue;
            JsonObject it = item(contents[i]);
            it.addProperty("slot", i);
            inv.add(it);
        }
        o.add("inventory", inv);
        InventoryView view = p.getOpenInventory();
        if (menuOpen(view)) o.addProperty("openMenu", plain(view.title()));
        else o.add("openMenu", JsonNull.INSTANCE);
        return o;
    }

    /**
     * The bot's open menu in the same shape as the client gui_read; open:false when no menu is open. With
     * `inventory`, and no menu open, the bot's own inventory as a player sees it with E (raw slots: 5-8 armour,
     * 9-35 main, 36-44 hotbar, 45 offhand).
     */
    public static JsonObject gui(Bot b, boolean inventory) {
        InventoryView view = b.bukkit().getOpenInventory();
        JsonObject o = new JsonObject();
        boolean open = menuOpen(view);
        o.addProperty("open", open);
        if (!open && !inventory) return o;
        if (!open) o.addProperty("inventory", true);
        o.addProperty("title", open ? plain(view.title()) : "Inventory");
        o.addProperty("type", view.getType().name());
        // The server's own slot list: Bukkit's countSlots() can count more slots than the menu has raw indices.
        int count = b.player().containerMenu.slots.size();
        o.addProperty("slotCount", count);
        int topSize = view.getTopInventory().getSize();
        JsonArray slots = new JsonArray();
        for (int raw = 0; raw < count; raw++) {
            ItemStack s = view.getItem(raw);
            if (s == null || s.getType().isAir()) continue;
            JsonObject it = item(s);
            it.addProperty("slot", raw);
            it.addProperty("container", raw < topSize ? "menu" : "player");
            slots.add(it);
        }
        o.add("slots", slots);
        return o;
    }

    /** A player's own inventory counts as "open" to Bukkit; only a real menu (chest, plugin GUI, …) counts here. */
    static boolean menuOpen(InventoryView view) {
        return view.getTopInventory().getType() != InventoryType.CRAFTING;
    }

    static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
