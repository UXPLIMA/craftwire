package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("xpos") void craftwire$setXpos(double x);
    @Accessor("ypos") void craftwire$setYpos(double y);
}
