package com.uxplima.craftwire.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public final class ItemJson {
    private ItemJson() {}

    public static JsonObject of(ItemStack stack, boolean withLore) {
        JsonObject o = new JsonObject();
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        o.addProperty("count", stack.getCount());
        o.addProperty("name", stack.getHoverName().getString());
        o.addProperty("enchanted", stack.hasFoil());
        Minecraft mc = Minecraft.getInstance();
        if (withLore && mc.player != null && mc.level != null) {
            List<Component> lines = stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
            JsonArray lore = new JsonArray();
            for (int i = 1; i < lines.size(); i++) lore.add(lines.get(i).getString());
            o.add("lore", lore);
        }
        return o;
    }
}
