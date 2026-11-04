package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The sign editor's line buffer: `removed()` sends exactly these lines in ServerboundSignUpdatePacket. The side it edits
 * is a different field per version, so ClientCompat.signFront reads that.
 */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
    @Accessor("messages") String[] craftwire$getMessages();
}
