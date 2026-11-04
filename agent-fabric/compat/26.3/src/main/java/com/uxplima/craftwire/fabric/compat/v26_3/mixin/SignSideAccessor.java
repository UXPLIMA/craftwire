package com.uxplima.craftwire.fabric.compat.v26_3.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which side the sign editor edits: from 26.3 a SignTextSlot (26.2 had the boolean isFrontText). */
@Mixin(AbstractSignEditScreen.class)
public interface SignSideAccessor {
    @Accessor("slot")
    SignTextSlot craftwire$slot();
}
