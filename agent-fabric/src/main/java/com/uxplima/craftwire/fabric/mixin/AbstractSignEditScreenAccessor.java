package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sign editor's line buffer: `removed()` sends exactly these lines in ServerboundSignUpdatePacket. */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
    @Accessor("messages") String[] craftwire$getMessages();

    @Accessor("isFrontText") boolean craftwire$isFrontText();
}
