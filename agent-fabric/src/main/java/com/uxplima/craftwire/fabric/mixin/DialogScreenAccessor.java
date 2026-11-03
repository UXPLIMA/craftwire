package com.uxplima.craftwire.fabric.mixin;

import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.server.dialog.Dialog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The dialog a dialog screen shows, so dialog_answer picks buttons and inputs by the server's own model. */
@Mixin(DialogScreen.class)
public interface DialogScreenAccessor {
    @Accessor("dialog") Dialog craftwire$getDialog();
}
