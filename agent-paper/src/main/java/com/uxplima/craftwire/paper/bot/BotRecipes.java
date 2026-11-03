package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.ScreenAction;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;

/** The beacon, the recipe book and the creative inventory: screens whose packets name registry entries. Bot thread. */
final class BotRecipes {
    private BotRecipes() {}

    static void beacon(Bot b, ScreenAction.Beacon a, JsonObject r) {
        if (!(b.player().containerMenu instanceof BeaconMenu)) {
            throw new AgentError("WRONG_SCREEN", b.name() + " does not have a beacon open", "use the beacon block first.");
        }
        Optional<Holder<MobEffect>> primary = Optional.of(effect(a.primary()));
        Optional<Holder<MobEffect>> secondary = a.secondary() == null ? Optional.empty() : Optional.of(effect(a.secondary()));
        b.listener().handleSetBeaconPacket(new ServerboundSetBeaconPacket(primary, secondary));
        r.addProperty("primary", a.primary());
        if (a.secondary() != null) r.addProperty("secondary", a.secondary());
    }

    private static Holder<MobEffect> effect(String id) {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (key == null) throw BotErrors.invalid("Not an effect id: " + id);
        return BuiltInRegistries.MOB_EFFECT.get(key).<Holder<MobEffect>>map(h -> h)
                .orElseThrow(() -> BotErrors.invalid("Unknown effect " + id + "; e.g. speed, haste, strength, regeneration"));
    }

    /** By display id, or by recipe id: the display ids the server assigned to that recipe (the first that places). */
    static void place(Bot b, ScreenAction.Recipe a, JsonObject r) {
        BotGuiActions.requireMenu(b);
        int container = b.player().containerMenu.containerId;
        List<RecipeDisplayId> ids = new ArrayList<>();
        if (a.displayId() != null) {
            ids.add(new RecipeDisplayId(a.displayId()));
        } else {
            Identifier key = Identifier.tryParse(a.recipeKey());
            if (key == null) throw BotErrors.invalid("Not a recipe id: " + a.recipeKey());
            MinecraftServer.getServer().getRecipeManager().listDisplaysForRecipe(ResourceKey.create(Registries.RECIPE, key), e -> ids.add(e.id()));
            if (ids.isEmpty()) {
                throw new AgentError("RECIPE_NOT_FOUND", "No recipe " + a.recipeKey(), "Use a recipe id such as minecraft:oak_planks.");
            }
        }
        b.listener().handlePlaceRecipe(new ServerboundPlaceRecipePacket(container, ids.getFirst(), a.all()));
        r.addProperty("recipe", ids.getFirst().index());
        r.addProperty("all", a.all());
    }

    /** Sets a slot of the creative inventory (the server accepts it only from players in creative mode). */
    static void creative(Bot b, ScreenAction.Creative a, JsonObject r) {
        if (!b.player().hasInfiniteMaterials()) {
            throw new AgentError("NOT_CREATIVE", b.name() + " is not in creative mode", "server_command gamemode creative " + b.name() + " first.");
        }
        ItemStack stack;
        try {
            stack = Bukkit.getItemFactory().createItemStack(a.item());
        } catch (IllegalArgumentException e) {
            throw BotErrors.invalid("Unknown item: " + a.item());
        }
        stack.setAmount(a.count());
        b.listener().handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(a.slot(), CraftItemStack.asNMSCopy(stack)));
        r.addProperty("slot", a.slot());
        r.add("item", BotJson.item(stack).json());
    }
}
