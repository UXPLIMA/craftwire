package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.core.AgentError;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * Body and hand verbs: jump, sneak, sprint, drop, swap_hands, give and select_hotbar. Each goes through the server's
 * packet handler where a client would send one, so the matching Bukkit event fires.
 */
final class BotInventoryActions {
    private BotInventoryActions() {}

    static CompletableFuture<JsonElement> run(String action, JsonObject p, BotManager bots, String name) {
        return bots.on(name, b -> switch (action) {
            case "jump" -> jump(b);
            case "sneak", "sprint" -> toggle(b, action, Params.bool(p, "on", true));
            case "drop" -> drop(b, Params.bool(p, "all", false));
            case "swap_hands" -> swapHands(b);
            case "give" -> give(b, p);
            case "select_hotbar" -> selectHotbar(b, Params.integer(p, "slot"));
            default -> throw BotErrors.invalid("Unknown bot action: " + action);
        });
    }

    private static JsonElement jump(Bot b) {
        boolean grounded = b.player().onGround();
        b.jump();
        JsonObject r = new JsonObject();
        r.addProperty("jumped", grounded);
        if (!grounded) r.addProperty("reason", "not on the ground");
        return r;
    }

    /** Sneak through the input packet (PlayerToggleSneakEvent), sprint through the player command (PlayerToggleSprintEvent). */
    private static JsonElement toggle(Bot b, String what, boolean on) {
        ServerPlayer p = b.player();
        if (what.equals("sneak")) {
            b.listener().handlePlayerInput(new ServerboundPlayerInputPacket(new Input(false, false, false, false, false, on, p.isSprinting())));
        } else {
            b.sprint(on);
        }
        JsonObject r = new JsonObject();
        r.addProperty("sneaking", b.bukkit().isSneaking());
        r.addProperty("sprinting", b.bukkit().isSprinting());
        return r;
    }

    /** Drops the held item, or the whole stack (PlayerDropItemEvent). */
    private static JsonElement drop(Bot b, boolean all) {
        ItemStack before = b.bukkit().getInventory().getItemInMainHand().clone();
        if (before.getType().isAir()) {
            throw new AgentError("NOTHING_HELD", b.name() + " holds nothing", "give the bot an item, or select_hotbar a slot that has one.");
        }
        b.listener().handlePlayerAction(new ServerboundPlayerActionPacket(
                all ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS : ServerboundPlayerActionPacket.Action.DROP_ITEM,
                BlockPos.ZERO, Direction.DOWN));
        ItemStack after = b.bukkit().getInventory().getItemInMainHand();
        int dropped = before.getAmount() - (after.isSimilar(before) ? after.getAmount() : 0);
        JsonObject r = new JsonObject();
        JsonObject item = BotJson.item(before).withCount(dropped).json();
        r.add("dropped", dropped > 0 ? item : null);
        if (dropped == 0) r.addProperty("cancelled", true);
        if (!after.getType().isAir()) r.add("held", BotJson.item(after).json());
        return r;
    }

    /** Swaps the main and off hand (PlayerSwapHandItemsEvent). */
    private static JsonElement swapHands(Bot b) {
        b.listener().handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ZERO, Direction.DOWN));
        JsonObject r = new JsonObject();
        ItemStack main = b.bukkit().getInventory().getItemInMainHand();
        ItemStack off = b.bukkit().getInventory().getItemInOffHand();
        if (!main.getType().isAir()) r.add("mainHand", BotJson.item(main).json());
        if (!off.getType().isAir()) r.add("offHand", BotJson.item(off).json());
        return r;
    }

    private static JsonElement give(Bot b, JsonObject p) {
        String id = Params.string(p, "item");
        ItemStack stack;
        try {
            stack = Bukkit.getItemFactory().createItemStack(id);
        } catch (IllegalArgumentException e) {
            throw new AgentError("INVALID_PARAMS", "Unknown item: " + id,
                    "Use an item id like diamond_sword, optionally with components: diamond_sword[enchantments={sharpness:5}].");
        }
        int count = Params.clampedInt(p, "count", 1, 1, 2304);
        stack.setAmount(1);
        int given = 0;
        int leftover = 0;
        for (int i = 0; i < count; i++) {
            if (b.bukkit().getInventory().addItem(stack.clone()).isEmpty()) given++;
            else leftover++;
        }
        JsonObject r = new JsonObject();
        r.addProperty("given", given);
        r.addProperty("leftover", leftover);
        return r;
    }

    /** Through the packet handler so PlayerItemHeldEvent fires as for a real client. */
    static JsonElement selectHotbar(Bot b, int slot) {
        if (slot < 0 || slot > 8) throw BotErrors.invalid("slot must be 0-8");
        b.listener().handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
        JsonObject r = new JsonObject();
        r.addProperty("slot", b.bukkit().getInventory().getHeldItemSlot());
        ItemStack held = b.bukkit().getInventory().getItemInMainHand();
        if (!held.getType().isAir()) r.add("held", BotJson.item(held).json());
        return r;
    }
}
