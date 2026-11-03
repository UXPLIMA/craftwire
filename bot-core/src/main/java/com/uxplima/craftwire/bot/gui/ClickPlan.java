package com.uxplima.craftwire.bot.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a vocabulary click into the container click packets a vanilla client sends for it (from
 * AbstractContainerScreen's mouse handling): SWAP takes the hotbar index or 40 for the off hand, THROW 0 for one
 * item and 1 for the stack, a double click is PICKUP followed by PICKUP_ALL, and a drag is QUICK_CRAFT start,
 * one QUICK_CRAFT per slot and QUICK_CRAFT end, with the button field holding the quick-craft mask.
 */
public final class ClickPlan {
    /** SWAP's button for the off hand (Inventory.SLOT_OFFHAND). */
    public static final int OFFHAND_BUTTON = 40;

    private ClickPlan() {}

    public static List<RawClick> of(Click c) {
        return switch (c.type()) {
            case LEFT -> List.of(RawClick.pickup(c.slot(), 0));
            case RIGHT -> List.of(RawClick.pickup(c.slot(), 1));
            case SHIFT -> List.of(new RawClick(c.slot(), 0, ContainerMode.QUICK_MOVE));
            case NUMBER -> List.of(new RawClick(c.slot(), c.key() - 1, ContainerMode.SWAP));
            case OFFHAND -> List.of(new RawClick(c.slot(), OFFHAND_BUTTON, ContainerMode.SWAP));
            case DROP -> List.of(new RawClick(c.slot(), 0, ContainerMode.THROW));
            case DROP_STACK -> List.of(new RawClick(c.slot(), 1, ContainerMode.THROW));
            case MIDDLE -> List.of(new RawClick(c.slot(), 2, ContainerMode.CLONE));
            case DOUBLE -> List.of(RawClick.pickup(c.slot(), 0), new RawClick(c.slot(), 0, ContainerMode.PICKUP_ALL));
            case OUTSIDE -> List.of(RawClick.pickup(RawClick.OUTSIDE, c.button().equals("right") ? 1 : 0));
            case DRAG -> drag(c);
        };
    }

    /**
     * A sweep over one slot is an ordinary click (the client sends no quick-craft then). Quick-craft type: 0 splits
     * evenly (left), 1 places one each (right), 2 clones full stacks (middle, creative).
     */
    private static List<RawClick> drag(Click c) {
        int type = switch (c.button()) {
            case "right" -> 1;
            case "middle" -> 2;
            default -> 0;
        };
        if (c.slots().size() == 1) {
            int slot = c.slots().getFirst();
            return type == 2 ? List.of(new RawClick(slot, 2, ContainerMode.CLONE)) : List.of(RawClick.pickup(slot, type));
        }
        List<RawClick> out = new ArrayList<>();
        out.add(new RawClick(RawClick.OUTSIDE, mask(0, type), ContainerMode.QUICK_CRAFT));
        for (int slot : c.slots()) out.add(new RawClick(slot, mask(1, type), ContainerMode.QUICK_CRAFT));
        out.add(new RawClick(RawClick.OUTSIDE, mask(2, type), ContainerMode.QUICK_CRAFT));
        return out;
    }

    /** AbstractContainerMenu.getQuickcraftMask: header (0 start, 1 add slot, 2 end) in bits 0-1, type in bits 2-3. */
    static int mask(int header, int type) {
        return (header & 3) | (type & 3) << 2;
    }
}
