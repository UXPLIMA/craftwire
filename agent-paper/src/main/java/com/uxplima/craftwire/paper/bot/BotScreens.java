package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.paper.compat.ServerCompat;
import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;

/**
 * Screens the server opened for the bot that are not container menus: the sign editor, a dialog and a book. Set by
 * packets from any thread, read on the bot's thread.
 */
final class BotScreens {
    private volatile ServerCompat.SignTarget sign;
    private volatile Holder<Dialog> dialog;
    private volatile boolean book;

    ServerCompat.SignTarget sign() {
        return sign;
    }

    void sign(ServerCompat.SignTarget s) {
        sign = s;
    }

    Holder<Dialog> dialog() {
        return dialog;
    }

    void dialog(Holder<Dialog> d) {
        dialog = d;
    }

    boolean book() {
        return book;
    }

    void book(boolean open) {
        book = open;
    }
}
