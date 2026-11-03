package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which hand holds the book being edited: ServerboundEditBookPacket names its inventory slot. */
@Mixin(BookEditScreen.class)
public interface BookEditScreenAccessor {
    @Accessor("hand") InteractionHand craftwire$getHand();
}
