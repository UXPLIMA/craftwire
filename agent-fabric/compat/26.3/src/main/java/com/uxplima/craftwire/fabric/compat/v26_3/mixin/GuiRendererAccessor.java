package com.uxplima.craftwire.fabric.compat.v26_3.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiRenderer.class)
public interface GuiRendererAccessor {
    @Accessor("renderState")
    GuiRenderState craftwire$renderState();
}
