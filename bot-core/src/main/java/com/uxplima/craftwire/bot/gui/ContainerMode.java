package com.uxplima.craftwire.bot.gui;

/**
 * The vanilla container click modes (ContainerInput in 26.x, ClickType before), in protocol id order. Every Java bot
 * kind maps these one to one; the Bedrock bot translates them into ItemStackRequest actions.
 */
public enum ContainerMode {
    PICKUP,
    QUICK_MOVE,
    SWAP,
    CLONE,
    THROW,
    QUICK_CRAFT,
    PICKUP_ALL;

    /** The id in ServerboundContainerClickPacket (the enum ordinal in vanilla). */
    public int id() {
        return ordinal();
    }
}
