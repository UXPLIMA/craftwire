package com.uxplima.craftwire.fabric.compat.v26_3.mixin;

import java.util.List;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiRenderState.class)
public interface GuiRenderStateAccessor {
    /** GuiRenderState$Node is package-private: the strata are handled as plain objects. */
    @Accessor("strata")
    List<Object> craftwire$strata();
}
