package com.uxplima.craftwire.bot.gui;

/** One step of a GUI helper: a container click, or a hotbar selection (ServerboundSetCarriedItemPacket). */
public sealed interface GuiStep {
    record ClickStep(RawClick click) implements GuiStep {}

    record HotbarStep(int slot) implements GuiStep {}

    static GuiStep left(int slot) {
        return new ClickStep(RawClick.pickup(slot, 0));
    }

    static GuiStep right(int slot) {
        return new ClickStep(RawClick.pickup(slot, 1));
    }

    static GuiStep of(RawClick c) {
        return new ClickStep(c);
    }
}
