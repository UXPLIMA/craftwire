package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.mc.DialogModel;
import com.uxplima.craftwire.mc.MenuInfo;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/**
 * What a special screen shows beyond its slots, for gui_read's `screen`: anvil cost, merchant offers, enchanting
 * costs, stonecutter and loom choices, beacon levels, lectern page, and an open sign editor or dialog. Bot thread.
 */
final class BotScreenInfo {
    private BotScreenInfo() {}

    /** Null when nothing beyond the slots is open. */
    static JsonObject of(Bot b) {
        JsonObject o = new JsonObject();
        AbstractContainerMenu menu = b.player().containerMenu;
        MenuInfo.fill(o, menu, stack -> BotJson.item(CraftItemStack.asBukkitCopy(stack)).json());
        // Paper widens AnvilMenu.itemName; the client cannot read it, so only the plugin bot reports the typed name.
        if (menu instanceof AnvilMenu a && a.itemName != null) o.addProperty("name", a.itemName);
        ServerCompat.SignTarget sign = b.link().screens.sign();
        if (sign != null) {
            JsonObject s = new JsonObject();
            s.addProperty("x", sign.pos().getX());
            s.addProperty("y", sign.pos().getY());
            s.addProperty("z", sign.pos().getZ());
            s.addProperty("side", sign.front() ? "front" : "back");
            o.add("sign", s);
        }
        Holder<Dialog> dialog = b.link().screens.dialog();
        if (dialog != null) o.add("dialog", DialogModel.json(dialog.value()));
        if (b.link().screens.book()) o.addProperty("book", true);
        return o.isEmpty() ? null : o;
    }
}
