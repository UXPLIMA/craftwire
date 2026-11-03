package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.ItemInfo;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/** Vanilla item stacks in the shape every bot kind reports: id, count, display name, lore lines, stack limit. */
final class JavaItems {
    private JavaItems() {}

    /** Null for an empty stack. */
    static ItemInfo info(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        List<String> lore = new ArrayList<>();
        ItemLore l = stack.get(DataComponents.LORE);
        if (l != null) for (Component c : l.lines()) lore.add(c.getString());
        return new ItemInfo(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), stack.getHoverName().getString(), lore,
                stack.getMaxStackSize());
    }

    static JsonObject json(ItemStack stack) {
        ItemInfo i = info(stack);
        return i == null ? new JsonObject() : i.json();
    }
}
