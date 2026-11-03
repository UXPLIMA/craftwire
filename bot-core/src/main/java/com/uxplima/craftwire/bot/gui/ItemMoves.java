package com.uxplima.craftwire.bot.gui;

import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.model.ItemInfo;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans the GUI helpers as the clicks a player would make, from one snapshot: move_item (pick up, place, put back
 * what is left), transfer (shift-click matching stacks to the other side) and equip (armour slots, off hand, main
 * hand). The plan never leaves an item on the cursor when the server accepts each click.
 */
public final class ItemMoves {
    /** Partial moves place one item per right click; more than a stack is never asked. */
    private static final int MAX_PARTIAL = 64;

    private ItemMoves() {}

    /** Moves `count` items (null = the whole stack) from one slot to another of the same menu. */
    public static List<GuiStep> moveItem(GuiView v, int from, int to, Integer count) {
        if (from == to) throw BotErrors.invalid("from and to are the same slot");
        if (to < 0 || to >= v.slotCount()) throw BotErrors.slotOutOfRange(to, v.slotCount());
        if (from < 0 || from >= v.slotCount()) throw BotErrors.slotOutOfRange(from, v.slotCount());
        requireEmptyCursor(v);
        ItemInfo a = v.item(from).orElseThrow(() -> emptySlot(from));
        ItemInfo b = v.item(to).orElse(null);
        int n = count == null ? a.count() : Math.clamp(count, 1, a.count());
        List<GuiStep> out = new ArrayList<>();
        if (n == a.count()) {
            out.add(GuiStep.left(from));
            out.add(GuiStep.left(to));
            boolean swapped = b != null && !b.sameItem(a);
            boolean overflow = b != null && b.sameItem(a) && b.count() + a.count() > Math.max(1, a.maxStack());
            if (swapped || overflow) out.add(GuiStep.left(from));
            return out;
        }
        if (b != null && !b.sameItem(a)) {
            throw new AgentError("TARGET_OCCUPIED", "Slot " + to + " holds " + b.id() + "; a partial move needs it empty or holding the same item",
                    "Move the whole stack (leave out count), or pick an empty slot.");
        }
        int room = Math.max(1, a.maxStack()) - (b == null ? 0 : b.count());
        n = Math.min(Math.min(n, room), MAX_PARTIAL);
        if (n <= 0) throw new AgentError("TARGET_FULL", "Slot " + to + " is full", "Pick another slot.");
        out.add(GuiStep.left(from));
        for (int i = 0; i < n; i++) out.add(GuiStep.right(to));
        out.add(GuiStep.left(from));
        return out;
    }

    /**
     * Shift-clicks stacks matching `item` (null = any) out of one side of an open menu until at least `count` items
     * (null = all) were moved. `fromMenu` picks the menu side, else the player's side.
     */
    public static List<GuiStep> transfer(GuiView v, boolean fromMenu, String item, Integer count) {
        if (fromMenu && !v.open()) throw BotErrors.invalid("from:'container' needs an open menu; the bot has only its inventory open");
        List<GuiStep> out = new ArrayList<>();
        int moved = 0;
        for (SlotItem s : v.slots()) {
            boolean menuSide = v.open() ? s.slot() < v.topSize() : s.slot() < PlayerSlots.MAIN_START;
            if (menuSide != fromMenu || !s.item().matches(item)) continue;
            if (!v.open() && s.slot() == PlayerSlots.CRAFT_RESULT) continue;
            out.add(GuiStep.of(new RawClick(s.slot(), 0, ContainerMode.QUICK_MOVE)));
            moved += s.item().count();
            if (count != null && moved >= count) break;
        }
        if (out.isEmpty()) {
            throw new AgentError("ITEM_NOT_FOUND", "No " + (item == null ? "items" : item) + " on the " + (fromMenu ? "menu" : "player") + " side",
                    "Check gui_read; item takes an id (diamond) or a display name.");
        }
        return out;
    }

    /**
     * Puts an item on: `to` is hand, offhand, head, chest, legs or feet. The source is the raw inventory slot `slot`,
     * or the first slot holding `item`. Needs the bot's own inventory (no menu open); `heldSlot` is the selected
     * hotbar slot 0-8.
     */
    public static List<GuiStep> equip(GuiView inv, int heldSlot, Integer slot, String item, String to) {
        if (inv.open()) {
            throw new AgentError("MENU_OPEN", "equip works on the bot's own inventory; a menu is open", "Close the menu first (gui_close).");
        }
        requireEmptyCursor(inv);
        int src = slot != null ? slot : find(inv, item);
        if (inv.item(src).isEmpty()) throw emptySlot(src);
        List<GuiStep> out = new ArrayList<>();
        switch (to) {
            case "offhand" -> {
                if (src != PlayerSlots.OFFHAND) out.add(GuiStep.of(new RawClick(src, ClickPlan.OFFHAND_BUTTON, ContainerMode.SWAP)));
            }
            case "hand" -> {
                if (PlayerSlots.hotbar(src)) out.add(new GuiStep.HotbarStep(src - PlayerSlots.HOTBAR_START));
                else out.add(GuiStep.of(new RawClick(src, heldSlot, ContainerMode.SWAP)));
            }
            default -> {
                int dst = PlayerSlots.armor(to);
                if (dst == src) return out;
                out.add(GuiStep.left(src));
                out.add(GuiStep.left(dst));
                // Puts back the armour that was worn, or the item itself when the slot refused it.
                out.add(GuiStep.left(src));
            }
        }
        return out;
    }

    private static int find(GuiView inv, String item) {
        if (item == null) throw BotErrors.invalid("equip needs `slot` or `item`");
        for (SlotItem s : inv.slots()) {
            if (s.slot() >= PlayerSlots.MAIN_START && s.slot() <= PlayerSlots.OFFHAND && s.item().matches(item)) return s.slot();
        }
        throw new AgentError("ITEM_NOT_FOUND", "No " + item + " in the inventory", "give the bot one first, or check gui_read inventory:true.");
    }

    private static AgentError emptySlot(int slot) {
        return new AgentError("EMPTY_SLOT", "Slot " + slot + " is empty", "Pick a slot that has an item (gui_read).");
    }

    private static void requireEmptyCursor(GuiView v) {
        if (v.carried() != null) {
            throw new AgentError("CURSOR_NOT_EMPTY", "The cursor holds " + v.carried().id(),
                    "Put it down first: gui_click a free slot, or click:'outside' to drop it.");
        }
    }
}
