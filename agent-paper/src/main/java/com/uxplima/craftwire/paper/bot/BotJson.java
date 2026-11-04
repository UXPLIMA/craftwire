package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.SlotItem;
import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.bot.model.BotState;
import com.uxplima.craftwire.bot.model.ItemInfo;
import com.uxplima.craftwire.bot.model.Position;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemInstance;
import org.bukkit.Location;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/** The plugin bot's state, items and menus in bot-core's shapes, so every bot kind reports the same JSON. Bot thread. */
public final class BotJson {
    private BotJson() {}

    public static JsonObject summary(Bot b) {
        Location l = b.bukkit().getLocation();
        return BotState.summary(b.name(), BotKind.PLUGIN, b.uuid().toString(), b.group(), l.getWorld().getName(),
                new Position(l.getX(), l.getY(), l.getZ()));
    }

    /** A server stack, copied through ItemInstance: 26.3 dropped asBukkitCopy(ItemStack) and kept only that overload. */
    public static ItemInfo item(ItemInstance stack) {
        return item(CraftItemStack.asBukkitCopy(stack));
    }

    public static ItemInfo item(ItemStack s) {
        List<String> lore = new ArrayList<>();
        List<Component> l = s.lore();
        if (l != null) l.forEach(c -> lore.add(plain(c)));
        return new ItemInfo(s.getType().getKey().toString(), s.getAmount(), plain(s.effectiveName()), lore, s.getMaxStackSize());
    }

    public static BotState state(Bot b) {
        Player p = b.bukkit();
        Location l = p.getLocation();
        List<SlotItem> inv = new ArrayList<>();
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null || contents[i].getType().isAir()) continue;
            inv.add(new SlotItem(i, false, item(contents[i])));
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        InventoryView view = p.getOpenInventory();
        return new BotState(b.name(), BotKind.PLUGIN, b.uuid().toString(), b.group(), l.getWorld().getName(),
                new Position(l.getX(), l.getY(), l.getZ()), l.getYaw(), l.getPitch(), p.getHealth(), p.getFoodLevel(),
                p.getGameMode().name().toLowerCase(Locale.ROOT), p.isOnGround(), p.isDead(), b.moving(), b.digging(), p.isSneaking(),
                p.isSprinting(), p.getInventory().getHeldItemSlot(), held.getType().isAir() ? null : item(held), inv,
                menuOpen(view) ? plain(view.title()) : null);
    }

    /**
     * The bot's open menu, in the same shape as the client gui_read; open:false when no menu is open. With
     * `inventory`, and no menu open, the bot's own inventory as a player sees it with E (raw slots: 5-8 armour,
     * 9-35 main, 36-44 hotbar, 45 offhand). The cursor item is `carried`; special screens add `screen`.
     */
    public static GuiView gui(Bot b, boolean inventory) {
        InventoryView view = b.bukkit().getOpenInventory();
        boolean open = menuOpen(view);
        JsonObject screen = BotScreenInfo.of(b);
        if (!open && !inventory) return new GuiView(false, null, null, -1, 0, 0, 0, List.of(), null, screen);
        AbstractContainerMenu menu = b.player().containerMenu;
        // The server's own slot list: Bukkit's countSlots() can count more slots than the menu has raw indices.
        int count = menu.slots.size();
        int topSize = view.getTopInventory().getSize();
        List<SlotItem> slots = new ArrayList<>();
        for (int raw = 0; raw < count; raw++) {
            ItemStack s = view.getItem(raw);
            if (s == null || s.getType().isAir()) continue;
            slots.add(new SlotItem(raw, raw < topSize, item(s)));
        }
        ItemInfo carried = menu.getCarried().isEmpty() ? null : item(menu.getCarried());
        return new GuiView(open, open ? plain(view.title()) : "Inventory", view.getType().name(), menu.containerId, menu.getStateId(),
                count, topSize, slots, carried, screen);
    }

    /** A player's own inventory counts as "open" to Bukkit; only a real menu (chest, plugin GUI, …) counts here. */
    static boolean menuOpen(InventoryView view) {
        return view.getTopInventory().getType() != InventoryType.CRAFTING;
    }

    static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}
