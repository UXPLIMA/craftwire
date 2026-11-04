package com.uxplima.craftwire.fabric.compat.v26_2.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which side the sign editor edits: 26.2 keeps a boolean (26.3 replaced it with a SignTextSlot). */
@Mixin(AbstractSignEditScreen.class)
public interface SignSideAccessor {
    @Accessor("isFrontText")
    boolean craftwire$isFrontText();
}
