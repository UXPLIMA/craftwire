package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos") int craftwire$getLeftPos();
    @Accessor("topPos") int craftwire$getTopPos();
    @Accessor("hoveredSlot") Slot craftwire$getHoveredSlot();
    @Invoker("slotClicked") void craftwire$slotClicked(Slot slot, int slotId, int button, ContainerInput input);
}
