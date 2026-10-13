package com.uxplima.craftwire.fabric.compat.v26_2.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererGuiAccessor {
    @Accessor("guiRenderer")
    GuiRenderer craftwire$guiRenderer();
}
