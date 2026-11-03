package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.mc.DialogModel;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import net.minecraft.core.Holder;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
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
        switch (menu) {
            case AnvilMenu a -> {
                o.addProperty("kind", "anvil");
                o.addProperty("cost", a.cost.get());
                if (a.itemName != null) o.addProperty("name", a.itemName);
            }
            case MerchantMenu m -> merchant(o, m);
            case EnchantmentMenu e -> {
                o.addProperty("kind", "enchanting");
                JsonArray costs = new JsonArray();
                for (int c : e.costs) costs.add(c);
                o.add("costs", costs);
                o.addProperty("lapis", e.getGoldCount());
            }
            case StonecutterMenu s -> {
                o.addProperty("kind", "stonecutter");
                o.addProperty("recipes", s.getNumberOfVisibleRecipes());
                o.addProperty("selected", s.getSelectedRecipeIndex());
            }
            case LoomMenu l -> {
                o.addProperty("kind", "loom");
                JsonArray patterns = new JsonArray();
                l.getSelectablePatterns().forEach(h -> patterns.add(h.getRegisteredName()));
                o.add("patterns", patterns);
                o.addProperty("selected", l.getSelectedBannerPatternIndex());
            }
            case BeaconMenu be -> {
                o.addProperty("kind", "beacon");
                o.addProperty("levels", be.getLevels());
                if (be.getPrimaryEffect() != null) o.addProperty("primary", be.getPrimaryEffect().getRegisteredName());
                if (be.getSecondaryEffect() != null) o.addProperty("secondary", be.getSecondaryEffect().getRegisteredName());
            }
            case LecternMenu le -> {
                o.addProperty("kind", "lectern");
                o.addProperty("page", le.getPage());
            }
            default -> { }
        }
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

    private static void merchant(JsonObject o, MerchantMenu m) {
        o.addProperty("kind", "merchant");
        o.addProperty("level", m.getTraderLevel());
        MerchantOffers offers = m.getOffers();
        JsonArray list = new JsonArray();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            JsonObject t = new JsonObject();
            t.addProperty("index", i);
            t.add("costA", BotJson.item(CraftItemStack.asBukkitCopy(offer.getCostA())).json());
            if (!offer.getCostB().isEmpty()) t.add("costB", BotJson.item(CraftItemStack.asBukkitCopy(offer.getCostB())).json());
            t.add("result", BotJson.item(CraftItemStack.asBukkitCopy(offer.getResult())).json());
            t.addProperty("uses", offer.getUses());
            t.addProperty("maxUses", offer.getMaxUses());
            t.addProperty("outOfStock", offer.isOutOfStock());
            list.add(t);
        }
        o.add("offers", list);
    }
}
