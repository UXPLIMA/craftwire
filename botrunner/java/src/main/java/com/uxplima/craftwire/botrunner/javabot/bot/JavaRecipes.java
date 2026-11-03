package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * The recipe book the server sent the bot. A client never learns recipe ids, only display ids and what each
 * recipe shows, so recipe_place by id picks the recipe whose result is that item (minecraft:oak_planks). Runner loop
 * thread.
 */
final class JavaRecipes {
    private final Map<Integer, RecipeDisplayEntry> known = new LinkedHashMap<>();

    void add(ClientboundRecipeBookAddPacket p) {
        if (p.replace()) known.clear();
        for (ClientboundRecipeBookAddPacket.Entry e : p.entries()) known.put(e.contents().id().index(), e.contents());
    }

    void remove(ClientboundRecipeBookRemovePacket p) {
        for (RecipeDisplayId id : p.recipes()) known.remove(id.index());
    }

    /** The display id of the first known recipe that makes `item`, or -1. */
    int byResult(String item, RegistryAccess access) {
        String want = item.contains(":") ? item : "minecraft:" + item;
        ContextMap context = context(access);
        for (RecipeDisplayEntry e : known.values()) {
            if (want.equals(result(e, context))) return e.id().index();
        }
        return -1;
    }

    JsonObject json(RegistryAccess access, int limit) {
        ContextMap context = context(access);
        JsonArray a = new JsonArray();
        for (RecipeDisplayEntry e : known.values()) {
            if (a.size() >= limit) break;
            JsonObject o = new JsonObject();
            o.addProperty("recipe", e.id().index());
            String r = result(e, context);
            if (r != null) o.addProperty("result", r);
            a.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("count", known.size());
        out.add("recipes", a);
        return out;
    }

    private static ContextMap context(RegistryAccess access) {
        ContextMap.Builder b = new ContextMap.Builder();
        if (access != null) b.withParameter(SlotDisplayContext.REGISTRIES, access);
        return b.create(SlotDisplayContext.CONTEXT);
    }

    private static String result(RecipeDisplayEntry e, ContextMap context) {
        try {
            ItemStack stack = e.display().result().resolveForFirstStack(context);
            return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } catch (RuntimeException ex) {
            // A display that needs context the bot lacks (fuel values) shows no result; it can still be placed by id.
            return null;
        }
    }
}
