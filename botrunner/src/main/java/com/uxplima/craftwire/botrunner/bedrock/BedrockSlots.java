package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.packet.BedrockItem;
import com.uxplima.craftwire.bedrock.packet.StackRequest;
import com.uxplima.craftwire.core.AgentError;

/**
 * Where a raw GUI slot lives for ItemStackRequest: its container (ContainerSlotType in the protocol) and slot, with
 * the stack network id the server last gave it. Servers map the inventory containers (hotbar, inventory, combined)
 * to the player inventory by slot index, and the open container's slots by their window index.
 */
final class BedrockSlots {
    static final int ARMOR = 6;
    static final int LEVEL_ENTITY = 7;
    static final int COMBINED_INVENTORY = 12;
    static final int FURNACE_FUEL = 24;
    static final int FURNACE_INGREDIENT = 25;
    static final int FURNACE_RESULT = 26;
    static final int HOTBAR = 28;
    static final int INVENTORY = 29;
    static final int SHULKER_BOX = 30;
    static final int OFFHAND = 34;
    static final int BLAST_FURNACE_INGREDIENT = 45;
    static final int SMOKER_INGREDIENT = 46;
    static final int BARREL = 58;
    static final int CURSOR = 59;
    /** The off hand is slot 1 of its container in stack requests (slot 0 of window 119 in InventoryContent). */
    private static final int OFFHAND_SLOT = 1;

    private BedrockSlots() {}

    static StackRequest.Slot cursor(BedrockInventory inv) {
        return new StackRequest.Slot(CURSOR, 0, inv.cursor.stackId());
    }

    /** A raw slot of the current GuiView (menu slots first, or Java's 46-slot inventory layout). */
    static StackRequest.Slot ofRaw(BedrockInventory inv, int raw) {
        if (!inv.menuOpen()) return ownInventory(inv, raw);
        int top = inv.menuSize();
        if (raw >= 0 && raw < top) return new StackRequest.Slot(menuContainer(inv, raw), raw, inv.menuItem(raw).stackId());
        int p = raw - top;
        if (p >= 0 && p < 27) return inventory(inv, 9 + p);
        if (p >= 27 && p < 36) return inventory(inv, p - 27);
        throw outside(raw, top + 36);
    }

    private static StackRequest.Slot ownInventory(BedrockInventory inv, int raw) {
        if (raw >= 5 && raw <= 8) return new StackRequest.Slot(ARMOR, raw - 5, inv.armour[raw - 5].stackId());
        if (raw >= 9 && raw <= 35) return inventory(inv, raw);
        if (raw >= 36 && raw <= 44) return inventory(inv, raw - 36);
        if (raw == 45) return offhand(inv);
        if (raw >= 0 && raw <= 4) {
            throw new AgentError("UNSUPPORTED_FOR_KIND", "Bedrock bots cannot use the 2x2 crafting grid by slot",
                    "Use the recipes and craft actions, or a crafting table.");
        }
        throw outside(raw, 46);
    }

    static StackRequest.Slot offhand(BedrockInventory inv) {
        return new StackRequest.Slot(OFFHAND, OFFHAND_SLOT, inv.offhand[0].stackId());
    }

    static StackRequest.Slot inventory(BedrockInventory inv, int index) {
        return new StackRequest.Slot(index < 9 ? HOTBAR : INVENTORY, index, inv.inventory[index].stackId());
    }

    /** The container a menu slot belongs to: chests and the like are one container, furnaces split by slot role. */
    private static int menuContainer(BedrockInventory inv, int slot) {
        String block = inv.menuBlock() == null ? "" : inv.menuBlock();
        return switch (inv.openMenu().type()) {
            case 2, 27, 28 -> switch (slot) {
                case 0 -> inv.openMenu().type() == 27 ? BLAST_FURNACE_INGREDIENT : inv.openMenu().type() == 28 ? SMOKER_INGREDIENT
                        : FURNACE_INGREDIENT;
                case 1 -> FURNACE_FUEL;
                default -> FURNACE_RESULT;
            };
            default -> block.endsWith("barrel") ? BARREL : block.endsWith("shulker_box") ? SHULKER_BOX : LEVEL_ENTITY;
        };
    }

    static BedrockItem get(BedrockInventory inv, StackRequest.Slot ref) {
        return switch (ref.containerId()) {
            case ARMOR -> ref.slot() < 4 ? inv.armour[ref.slot()] : null;
            case HOTBAR, INVENTORY, COMBINED_INVENTORY -> ref.slot() < 36 ? inv.inventory[ref.slot()] : null;
            case OFFHAND -> inv.offhand[0];
            case CURSOR -> inv.cursor;
            default -> inv.menuOpen() ? inv.menuItem(ref.slot()) : null;
        };
    }

    static void set(BedrockInventory inv, StackRequest.Slot ref, BedrockItem item) {
        switch (ref.containerId()) {
            case ARMOR -> {
                if (ref.slot() < 4) inv.armour[ref.slot()] = item;
            }
            case HOTBAR, INVENTORY, COMBINED_INVENTORY -> {
                if (ref.slot() < 36) inv.inventory[ref.slot()] = item;
            }
            case OFFHAND -> inv.offhand[0] = item;
            case CURSOR -> inv.cursor = item;
            default -> inv.menuItem(ref.slot(), item);
        }
    }

    private static AgentError outside(int raw, int count) {
        return new AgentError("INVALID_SLOT", "Slot " + raw + " is not in this menu (0-" + (count - 1) + ")", "gui_read lists the slots.");
    }
}
