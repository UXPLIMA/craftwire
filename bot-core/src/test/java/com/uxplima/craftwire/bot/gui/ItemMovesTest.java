package com.uxplima.craftwire.bot.gui;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.bot.model.ItemInfo;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The GUI helpers' plans, played on a small model of vanilla's PICKUP rules (AbstractContainerMenu.doClick): what
 * matters is where the items end up and that nothing is left on the cursor, not which clicks were chosen.
 */
class ItemMovesTest {
    private static ItemInfo item(String id, int count, int max) {
        return new ItemInfo("minecraft:" + id, count, id, List.of(), max);
    }

    /** A 27-slot chest above the player's 36 slots, with vanilla's left and right click rules. */
    private static final class Chest {
        final TreeMap<Integer, ItemInfo> slots = new TreeMap<>();
        ItemInfo cursor;

        Chest put(int slot, ItemInfo i) {
            slots.put(slot, i);
            return this;
        }

        GuiView view() {
            List<SlotItem> list = new ArrayList<>();
            slots.forEach((s, i) -> list.add(new SlotItem(s, s < 27, i)));
            return new GuiView(true, "Chest", "GENERIC_9X3", 1, 0, 63, 27, list, cursor, null);
        }

        void play(List<GuiStep> steps) {
            for (GuiStep step : steps) {
                RawClick c = ((GuiStep.ClickStep) step).click();
                assertEquals(ContainerMode.PICKUP, c.mode());
                if (c.button() == 0) left(c.slot());
                else right(c.slot());
            }
        }

        private void left(int slot) {
            ItemInfo s = slots.get(slot);
            if (cursor == null) {
                cursor = slots.remove(slot);
            } else if (s == null) {
                set(slot, cursor);
                cursor = null;
            } else if (s.sameItem(cursor)) {
                int moved = Math.min(cursor.count(), s.maxStack() - s.count());
                set(slot, s.withCount(s.count() + moved));
                cursor = cursor.count() == moved ? null : cursor.withCount(cursor.count() - moved);
            } else {
                set(slot, cursor);
                cursor = s;
            }
        }

        private void right(int slot) {
            ItemInfo s = slots.get(slot);
            if (cursor == null) {
                if (s == null) return;
                int take = (s.count() + 1) / 2;
                cursor = s.withCount(take);
                if (s.count() == take) slots.remove(slot);
                else set(slot, s.withCount(s.count() - take));
            } else if (s == null || (s.sameItem(cursor) && s.count() < s.maxStack())) {
                set(slot, cursor.withCount((s == null ? 0 : s.count()) + 1));
                cursor = cursor.count() == 1 ? null : cursor.withCount(cursor.count() - 1);
            } else if (!s.sameItem(cursor)) {
                set(slot, cursor);
                cursor = s;
            }
        }

        private void set(int slot, ItemInfo i) {
            slots.put(slot, i);
        }

        int count(int slot) {
            ItemInfo i = slots.get(slot);
            return i == null ? 0 : i.count();
        }
    }

    private static Chest moved(Chest c, int from, int to, Integer count) {
        c.play(ItemMoves.moveItem(c.view(), from, to, count));
        assertNull(c.cursor, "nothing is left on the cursor");
        return c;
    }

    @Test
    void aWholeStackMovesToAnEmptySlot() {
        Chest c = moved(new Chest().put(0, item("diamond", 20, 64)), 0, 30, null);
        assertEquals(0, c.count(0));
        assertEquals(20, c.count(30));
    }

    @Test
    void aWholeStackOntoAnotherItemSwapsThem() {
        Chest c = moved(new Chest().put(0, item("diamond", 20, 64)).put(5, item("stone", 3, 64)), 0, 5, null);
        assertEquals("minecraft:diamond", c.slots.get(5).id());
        assertEquals("minecraft:stone", c.slots.get(0).id());
    }

    @Test
    void aWholeStackOntoTheSameItemFillsItAndKeepsTheRest() {
        Chest c = moved(new Chest().put(0, item("diamond", 40, 64)).put(5, item("diamond", 40, 64)), 0, 5, null);
        assertEquals(64, c.count(5));
        assertEquals(16, c.count(0));
    }

    @Test
    void unstackableItemsOfTheSameKindStayWhereTheyWere() {
        Chest c = moved(new Chest().put(0, item("diamond_sword", 1, 1)).put(5, item("diamond_sword", 1, 1)), 0, 5, null);
        assertEquals(1, c.count(0));
        assertEquals(1, c.count(5));
    }

    @Test
    void aPartialMoveLeavesTheRestInTheSourceSlot() {
        Chest c = moved(new Chest().put(0, item("diamond", 20, 64)), 0, 30, 5);
        assertEquals(15, c.count(0));
        assertEquals(5, c.count(30));
    }

    @Test
    void aPartialMoveStopsWhenTheTargetIsFull() {
        Chest c = moved(new Chest().put(0, item("diamond", 20, 64)).put(30, item("diamond", 60, 64)), 0, 30, 10);
        assertEquals(64, c.count(30));
        assertEquals(16, c.count(0));
    }

    @Test
    void aPartialMoveOntoAnotherItemIsRefused() {
        Chest c = new Chest().put(0, item("diamond", 20, 64)).put(5, item("stone", 3, 64));
        assertEquals("TARGET_OCCUPIED", assertThrows(AgentError.class, () -> ItemMoves.moveItem(c.view(), 0, 5, 2)).code());
    }

    @Test
    void movesNeedAnEmptyCursorAndRealSlots() {
        Chest holding = new Chest().put(0, item("diamond", 20, 64));
        holding.cursor = item("stone", 1, 64);
        assertEquals("CURSOR_NOT_EMPTY", assertThrows(AgentError.class, () -> ItemMoves.moveItem(holding.view(), 0, 5, null)).code());
        Chest c = new Chest().put(0, item("diamond", 20, 64));
        assertEquals("EMPTY_SLOT", assertThrows(AgentError.class, () -> ItemMoves.moveItem(c.view(), 1, 5, null)).code());
        assertEquals("SLOT_OUT_OF_RANGE", assertThrows(AgentError.class, () -> ItemMoves.moveItem(c.view(), 0, 63, null)).code());
    }

    @Test
    void transferShiftClicksMatchingStacksOnTheChosenSideUntilEnough() {
        Chest c = new Chest().put(1, item("diamond", 10, 64)).put(2, item("stone", 64, 64)).put(4, item("diamond", 10, 64))
                .put(9, item("diamond", 10, 64)).put(40, item("diamond", 5, 64));
        List<GuiStep> steps = ItemMoves.transfer(c.view(), true, "diamond", 15);
        assertEquals(List.of(GuiStep.of(new RawClick(1, 0, ContainerMode.QUICK_MOVE)), GuiStep.of(new RawClick(4, 0, ContainerMode.QUICK_MOVE))), steps);
        List<GuiStep> back = ItemMoves.transfer(c.view(), false, "diamond", null);
        assertEquals(List.of(GuiStep.of(new RawClick(40, 0, ContainerMode.QUICK_MOVE))), back);
        assertEquals("ITEM_NOT_FOUND", assertThrows(AgentError.class, () -> ItemMoves.transfer(c.view(), false, "stone", null)).code());
    }

    private static GuiView inventory(SlotItem... items) {
        return new GuiView(false, "Inventory", "INVENTORY", 0, 0, PlayerSlots.COUNT, 0, List.of(items), null, null);
    }

    @Test
    void equipUsesTheKeysAPlayerWouldPress() {
        GuiView inv = inventory(new SlotItem(38, false, item("bread", 3, 64)), new SlotItem(12, false, item("shield", 1, 1)),
                new SlotItem(20, false, item("iron_helmet", 1, 1)));
        // a hotbar item is selected, not moved; anything else swaps into the held slot (number key)
        assertEquals(List.of(new GuiStep.HotbarStep(2)), ItemMoves.equip(inv, 0, null, "bread", "hand"));
        assertEquals(List.of(GuiStep.of(new RawClick(12, 4, ContainerMode.SWAP))), ItemMoves.equip(inv, 4, 12, null, "hand"));
        assertEquals(List.of(GuiStep.of(new RawClick(12, ClickPlan.OFFHAND_BUTTON, ContainerMode.SWAP))),
                ItemMoves.equip(inv, 0, null, "shield", "offhand"));
        assertEquals(List.of(GuiStep.left(20), GuiStep.left(PlayerSlots.HEAD), GuiStep.left(20)), ItemMoves.equip(inv, 0, null, "iron_helmet", "head"));
    }

    @Test
    void equipRefusesWhileAMenuIsOpen() {
        assertEquals("MENU_OPEN", assertThrows(AgentError.class, () -> ItemMoves.equip(new Chest().view(), 0, 0, null, "hand")).code());
    }
}
