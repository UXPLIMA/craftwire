package com.uxplima.craftwire.bot.gui;

/**
 * One ServerboundContainerClickPacket as the vanilla client sends it.
 *
 * @param slot   the raw slot index, or {@link #OUTSIDE} (-999) for clicks outside the window
 * @param button the packet's button field; its meaning depends on the mode (see {@link ClickPlan})
 */
public record RawClick(int slot, int button, ContainerMode mode) {
    /** The slot number vanilla uses for "outside the window" (AbstractContainerMenu.SLOT_CLICKED_OUTSIDE). */
    public static final int OUTSIDE = -999;

    public static RawClick pickup(int slot, int button) {
        return new RawClick(slot, button, ContainerMode.PICKUP);
    }
}
