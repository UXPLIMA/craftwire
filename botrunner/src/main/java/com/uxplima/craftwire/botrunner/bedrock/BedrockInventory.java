package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.packet.BedrockItem;
import com.uxplima.craftwire.bedrock.packet.ItemPackets;
import com.uxplima.craftwire.bedrock.packet.StackRequest;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.SlotItem;
import com.uxplima.craftwire.bot.model.ItemInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The bot's items as the server sent them, by window: inventory (0: hotbar 0-8, main 9-35), off hand (119), armour
 * (120), the UI window's cursor (124 slot 0) and the open container. Menus are shown with Java's raw slot layout
 * (menu slots, then main, then hotbar; the own inventory as Java's 46-slot inventory menu), so gui_read and
 * gui_click read the same on every kind. Runner loop thread.
 */
final class BedrockInventory {
    static final int WINDOW_INVENTORY = 0;
    static final int WINDOW_OFFHAND = 119;
    static final int WINDOW_ARMOUR = 120;
    static final int WINDOW_UI = 124;
    private static final int UI_CURSOR = 0;
    private static final String[] TYPES = {"CONTAINER", "CRAFTING", "FURNACE", "ENCHANTMENT", "BREWING_STAND", "ANVIL", "DISPENSER",
            "DROPPER", "HOPPER", "CAULDRON", "MINECART_CHEST", "MINECART_HOPPER", "HORSE", "BEACON", "STRUCTURE_EDITOR", "MERCHANT",
            "COMMAND_BLOCK", "JUKEBOX", "ARMOUR", "HAND", "COMPOUND_CREATOR", "ELEMENT_CONSTRUCTOR", "MATERIAL_REDUCER", "LAB_TABLE", "LOOM",
            "LECTERN", "GRINDSTONE", "BLAST_FURNACE", "SMOKER", "STONECUTTER", "CARTOGRAPHY", "HUD", "JIGSAW_EDITOR", "SMITHING",
            "CHEST_BOAT", "DECORATED_POT", "CRAFTER"};

    private final BedrockItems items;
    final BedrockItem[] inventory = filled(36);
    final BedrockItem[] armour = filled(4);
    final BedrockItem[] offhand = filled(1);
    BedrockItem cursor = BedrockItem.EMPTY;
    private ItemPackets.ContainerOpen open;
    private BedrockItem[] menu;
    private String menuBlock;
    private String menuName;

    BedrockInventory(BedrockItems items) {
        this.items = items;
    }

    BedrockItems items() {
        return items;
    }

    private static BedrockItem[] filled(int n) {
        BedrockItem[] a = new BedrockItem[n];
        Arrays.fill(a, BedrockItem.EMPTY);
        return a;
    }

    void content(ItemPackets.InventoryContent c) {
        BedrockItem[] target = window(c.window());
        if (target == null) {
            if (c.window() == WINDOW_UI && !c.items().isEmpty()) cursor = c.items().get(UI_CURSOR);
            return;
        }
        if (target == menu && c.items().size() != menu.length) {
            menu = c.items().toArray(new BedrockItem[0]);
            return;
        }
        for (int i = 0; i < Math.min(target.length, c.items().size()); i++) target[i] = c.items().get(i);
    }

    void slot(ItemPackets.InventorySlot s) {
        if (s.window() == WINDOW_UI) {
            if (s.slot() == UI_CURSOR) cursor = s.item();
            return;
        }
        BedrockItem[] target = window(s.window());
        if (target != null && s.slot() >= 0 && s.slot() < target.length) target[s.slot()] = s.item();
    }

    private BedrockItem[] window(int w) {
        return switch (w) {
            case WINDOW_INVENTORY -> inventory;
            case WINDOW_OFFHAND -> offhand;
            case WINDOW_ARMOUR -> armour;
            default -> open != null && open.window() == w ? menu : null;
        };
    }

    /**
     * ContainerOpen; the slots arrive in InventoryContent for the window. `block` is the block it belongs to and
     * `name` that block entity's CustomName, either null.
     */
    void opened(ItemPackets.ContainerOpen o, String block, String name) {
        if (o.type() < 0) {
            open = null;
            return;
        }
        open = o;
        menu = filled(0);
        menuBlock = block;
        menuName = name;
    }

    void closed() {
        open = null;
        menu = null;
        menuBlock = null;
        menuName = null;
    }

    boolean menuOpen() {
        return open != null;
    }

    ItemPackets.ContainerOpen openMenu() {
        return open;
    }

    String menuBlock() {
        return menuBlock;
    }

    int menuSize() {
        return menu == null ? 0 : menu.length;
    }

    BedrockItem menuItem(int slot) {
        return menu != null && slot >= 0 && slot < menu.length ? menu[slot] : BedrockItem.EMPTY;
    }

    void menuItem(int slot, BedrockItem item) {
        if (menu != null && slot >= 0 && slot < menu.length) menu[slot] = item;
    }

    String type() {
        if (open == null) return "INVENTORY";
        int t = open.type();
        if (t == 0) return "GENERIC_9X" + Math.max(1, menuSize() / 9);
        return t < TYPES.length ? TYPES[t] : "CONTAINER_" + t;
    }

    /** The menu's title: Bedrock sends none in ContainerOpen; it is the block entity's name, else the block's. */
    String title() {
        if (open == null) return "Inventory";
        if (menuName != null) return BedrockHud.plain(menuName);
        return menuBlock != null ? BedrockItems.display(menuBlock) : BedrockItems.display(type().toLowerCase(Locale.ROOT));
    }

    GuiView view() {
        List<SlotItem> out = new ArrayList<>();
        if (open == null) {
            add(out, 5, armour[0], false);
            add(out, 6, armour[1], false);
            add(out, 7, armour[2], false);
            add(out, 8, armour[3], false);
            for (int i = 9; i < 36; i++) add(out, i, inventory[i], false);
            for (int i = 0; i < 9; i++) add(out, 36 + i, inventory[i], false);
            add(out, 45, offhand[0], false);
            return new GuiView(false, "Inventory", "INVENTORY", WINDOW_INVENTORY, 0, 46, 0, out, items.info(cursor), null);
        }
        int top = menuSize();
        for (int i = 0; i < top; i++) add(out, i, menu[i], true);
        for (int i = 9; i < 36; i++) add(out, top + i - 9, inventory[i], false);
        for (int i = 0; i < 9; i++) add(out, top + 27 + i, inventory[i], false);
        return new GuiView(true, title(), type(), open.window(), 0, top + 36, top, out, items.info(cursor), null);
    }

    private void add(List<SlotItem> out, int raw, BedrockItem it, boolean inMenu) {
        ItemInfo info = items.info(it);
        if (info != null) out.add(new SlotItem(raw, inMenu, info));
    }

    /** The inventory by inventory index (0-8 hotbar, 9-35 main, 36-39 boots to helmet, 40 off hand), as BotState reports it. */
    List<SlotItem> byInventoryIndex() {
        List<SlotItem> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) add(out, i, inventory[i], false);
        for (int i = 0; i < 4; i++) add(out, 36 + i, armour[3 - i], false);
        add(out, 40, offhand[0], false);
        return out;
    }

    BedrockItem hotbar(int slot) {
        return inventory[slot];
    }

    /** Applies the server's answer to a stack request: new counts and stack ids of the slots it touched. */
    void confirmed(List<ItemPackets.SlotUpdate> slots) {
        for (ItemPackets.SlotUpdate u : slots) {
            StackRequest.Slot ref = new StackRequest.Slot(u.containerId(), u.slot(), u.stackId());
            BedrockItem old = BedrockSlots.get(this, ref);
            if (old == null) continue;
            BedrockItem now = u.count() == 0 || old.empty() ? BedrockItem.EMPTY
                    : new BedrockItem(old.networkId(), u.count(), old.meta(), u.stackId(), old.blockRuntimeId(), old.nbt(), old.userData());
            BedrockSlots.set(this, ref, now);
        }
    }
}
