package com.uxplima.craftwire.fabric.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.SlotItem;
import com.uxplima.craftwire.bot.model.ItemInfo;
import com.uxplima.craftwire.fabric.ItemJson;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import com.uxplima.craftwire.fabric.mixin.AbstractSignEditScreenAccessor;
import com.uxplima.craftwire.mc.MenuInfo;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The client's open menu in bot-core's GuiView shape, so the GUI helpers (move_item, transfer, equip) plan the same
 * clicks for the client as for every bot kind. Render thread.
 */
public final class ClientMenus {
    /** The player's 27 main and 9 hotbar slots that every container menu appends after its own. */
    private static final int PLAYER_SLOTS = 36;

    private ClientMenus() {}

    public static GuiView view(AbstractContainerScreen<?> screen) {
        AbstractContainerMenu menu = screen.getMenu();
        boolean open = !(menu instanceof InventoryMenu);
        List<Slot> slots = menu.slots;
        List<SlotItem> items = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s.hasItem()) items.add(new SlotItem(i, !(s.container instanceof Inventory), item(s.getItem())));
        }
        int topSize = open ? Math.max(0, slots.size() - PLAYER_SLOTS) : 0;
        ItemStack carried = menu.getCarried();
        return new GuiView(open, screen.getTitle().getString(), menuType(menu), menu.containerId, menu.getStateId(), slots.size(), topSize,
                items, carried.isEmpty() ? null : item(carried), screenInfo(screen));
    }

    public static ItemInfo item(ItemStack stack) {
        JsonObject j = ItemJson.of(stack, true);
        List<String> lore = new ArrayList<>();
        if (j.has("lore")) j.getAsJsonArray("lore").forEach(e -> lore.add(e.getAsString()));
        return new ItemInfo(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), stack.getHoverName().getString(), lore,
                stack.getMaxStackSize());
    }

    /** The registry id of the menu type (minecraft:generic_9x3, minecraft:anvil, …), or "inventory" for the player's own. */
    public static String menuType(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) return "inventory";
        try {
            return BuiltInRegistries.MENU.getKey(menu.getType()).toString();
        } catch (UnsupportedOperationException e) {
            // Menus without a registered type (the creative item picker) throw from getType().
            return "none";
        }
    }

    /** Anvil cost, merchant offers and the like for a menu screen; sign and book state for those editors; else null. */
    public static JsonObject screenInfo(Screen screen) {
        JsonObject o = new JsonObject();
        if (screen instanceof AbstractContainerScreen<?> acs) {
            MenuInfo.fill(o, acs.getMenu(), stack -> ItemJson.of(stack, false));
        } else if (screen instanceof AbstractSignEditScreen sign) {
            AbstractSignEditScreenAccessor acc = (AbstractSignEditScreenAccessor) sign;
            JsonObject s = new JsonObject();
            s.addProperty("side", ClientCompat.get().signFront(sign) ? "front" : "back");
            JsonArray lines = new JsonArray();
            for (String line : acc.craftwire$getMessages()) lines.add(line);
            s.add("lines", lines);
            o.add("sign", s);
        } else if (screen instanceof BookEditScreen) {
            o.addProperty("book", true);
        }
        return o.isEmpty() ? null : o;
    }

    /** The open screen if it has item slots, else null. */
    public static AbstractContainerScreen<?> containerScreen() {
        return Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> acs ? acs : null;
    }
}
