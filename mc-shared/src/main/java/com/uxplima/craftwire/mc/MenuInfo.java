package com.uxplima.craftwire.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.function.Function;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * What a special menu shows beyond its slots, for gui_read's `screen`: anvil cost, merchant offers, enchanting costs,
 * stonecutter and loom choices, beacon levels and the lectern page. The menu classes are the same on the server and the
 * client, so the plugin bot and the Fabric client report the same shape; `item` turns a stack into the caller's item
 * JSON. Runs on the thread that owns the menu.
 */
public final class MenuInfo {
    private MenuInfo() {}

    /** Fills `o` with the menu's state; leaves it untouched for menus that show nothing beyond their slots. */
    public static void fill(JsonObject o, AbstractContainerMenu menu, Function<ItemStack, JsonObject> item) {
        switch (menu) {
            case AnvilMenu a -> {
                o.addProperty("kind", "anvil");
                o.addProperty("cost", a.getCost());
            }
            case MerchantMenu m -> merchant(o, m, item);
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
    }

    private static void merchant(JsonObject o, MerchantMenu m, Function<ItemStack, JsonObject> item) {
        o.addProperty("kind", "merchant");
        o.addProperty("level", m.getTraderLevel());
        MerchantOffers offers = m.getOffers();
        JsonArray list = new JsonArray();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            JsonObject t = new JsonObject();
            t.addProperty("index", i);
            t.add("costA", item.apply(offer.getCostA()));
            if (!offer.getCostB().isEmpty()) t.add("costB", item.apply(offer.getCostB()));
            t.add("result", item.apply(offer.getResult()));
            t.addProperty("uses", offer.getUses());
            t.addProperty("maxUses", offer.getMaxUses());
            t.addProperty("outOfStock", offer.isOutOfStock());
            list.add(t);
        }
        o.add("offers", list);
    }
}
