package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Hud.class)
public interface HudAccessor {
    @Accessor("overlayMessageString") Component craftwire$getOverlayMessage();
    @Accessor("overlayMessageTime") int craftwire$getOverlayMessageTime();
    @Accessor("title") Component craftwire$getTitle();
    @Accessor("subtitle") Component craftwire$getSubtitle();
    @Accessor("titleTime") int craftwire$getTitleTime();
    @Accessor("bossOverlay") BossHealthOverlay craftwire$getBossOverlay();
    @Accessor("isHidden") boolean craftwire$isHidden();
    @Accessor("isHidden") void craftwire$setHidden(boolean hidden);
}
