package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.ScreenAction;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.botrunner.javabot.JavaCompat;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.mc.DialogModel;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/**
 * The special screens for a Java bot, each as the packet the vanilla client sends: anvil rename, sign and book
 * edits, merchant trades, menu buttons, beacon, recipe book, creative slots, dialogs and clickable chat. The menu
 * type is checked from what the server opened, as a client knows it. Runner loop thread.
 */
final class JavaScreenActions {
    private JavaScreenActions() {}

    static JsonObject act(JavaBot b, ScreenAction a) {
        JsonObject r = new JsonObject();
        switch (a) {
            case ScreenAction.Rename n -> {
                requireType(b, "minecraft:anvil", "an anvil");
                b.send(new ServerboundRenameItemPacket(n.text()));
                r.addProperty("renamed", n.text());
            }
            case ScreenAction.Sign s -> sign(b, s, r);
            case ScreenAction.Book k -> {
                int slot = k.slot() >= 0 ? k.slot() : b.life.heldSlot();
                b.send(new ServerboundEditBookPacket(slot, k.pages(), Optional.ofNullable(k.title())));
                b.screens.book(false);
                r.addProperty("pages", k.pages().size());
                r.addProperty("signed", k.title() != null);
            }
            case ScreenAction.Trade t -> {
                requireType(b, "minecraft:merchant", "a merchant");
                b.send(new ServerboundSelectTradePacket(t.index()));
                r.addProperty("selected", t.index());
            }
            case ScreenAction.Button btn -> {
                requireMenu(b);
                b.send(new ServerboundContainerButtonClickPacket(b.inventory.containerId(), btn.id()));
                r.addProperty("button", btn.id());
            }
            case ScreenAction.Beacon be -> beacon(b, be, r);
            case ScreenAction.Recipe rc -> recipe(b, rc, r);
            case ScreenAction.Creative c -> creative(b, c, r);
            case ScreenAction.Dialog d -> dialog(b, d, r);
            case ScreenAction.ChatClick c -> chatClick(b, c, r);
        }
        return r;
    }

    /** With no editor open for that sign, right-clicks it first: the server then lets the bot edit it, as for a player. */
    private static void sign(JavaBot b, ScreenAction.Sign s, JsonObject r) {
        JavaCompat.SignEditor open = b.screens.sign();
        BlockPos pos;
        boolean front = s.front();
        if (s.block() != null) {
            pos = new BlockPos(s.block().x(), s.block().y(), s.block().z());
            if (open == null || !open.pos().equals(pos)) b.screens.useOn(pos, Direction.UP, InteractionHand.MAIN_HAND);
        } else if (open != null) {
            pos = open.pos();
        } else {
            throw new AgentError("NO_SIGN_EDITOR", "No sign editor is open", "Pass block {x,y,z} of the sign, or right-click it first (use).");
        }
        b.send(b.ctx.compat().signUpdate(pos, front, s.lines()));
        b.screens.sign(null);
        r.addProperty("signed", pos.toShortString());
        r.addProperty("side", front ? "front" : "back");
    }

    private static void beacon(JavaBot b, ScreenAction.Beacon a, JsonObject r) {
        requireType(b, "minecraft:beacon", "a beacon");
        Optional<Holder<MobEffect>> primary = Optional.of(effect(a.primary()));
        Optional<Holder<MobEffect>> secondary = a.secondary() == null ? Optional.empty() : Optional.of(effect(a.secondary()));
        b.send(new ServerboundSetBeaconPacket(primary, secondary));
        r.addProperty("primary", a.primary());
        if (a.secondary() != null) r.addProperty("secondary", a.secondary());
    }

    private static Holder<MobEffect> effect(String id) {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (key == null) throw BotErrors.invalid("Not an effect id: " + id);
        return BuiltInRegistries.MOB_EFFECT.get(key).<Holder<MobEffect>>map(h -> h)
                .orElseThrow(() -> BotErrors.invalid("Unknown effect " + id + "; e.g. speed, haste, strength, regeneration"));
    }

    /** By display id, or by the item the recipe makes: the client is never told recipe ids. */
    private static void recipe(JavaBot b, ScreenAction.Recipe a, JsonObject r) {
        requireMenu(b);
        int display = a.displayId() != null ? a.displayId() : b.recipes.byResult(a.recipeKey(), b.registries);
        if (display < 0) {
            throw new AgentError("RECIPE_NOT_FOUND", "No recipe in " + b.name() + "'s recipe book makes " + a.recipeKey(),
                    "bot_action recipes lists the recipe book; the server only sends recipes the player has unlocked.");
        }
        b.send(new ServerboundPlaceRecipePacket(b.inventory.containerId(), new RecipeDisplayId(display), a.all()));
        r.addProperty("recipe", display);
        r.addProperty("all", a.all());
    }

    /** A plain item id (components are not parsed client-side); the server accepts it only from creative players. */
    private static void creative(JavaBot b, ScreenAction.Creative a, JsonObject r) {
        if (!b.creative) {
            throw new AgentError("NOT_CREATIVE", b.name() + " is not in creative mode", "server_command gamemode creative " + b.name() + " first.");
        }
        Identifier key = Identifier.tryParse(a.item().contains(":") ? a.item() : "minecraft:" + a.item());
        Holder<Item> item = key == null ? null : BuiltInRegistries.ITEM.get(key).<Holder<Item>>map(h -> h).orElse(null);
        if (item == null) throw BotErrors.invalid("Unknown item: " + a.item() + " (Java bots take plain item ids, without components)");
        ItemStack stack = new ItemStack(item, a.count());
        b.send(new ServerboundSetCreativeModeSlotPacket(a.slot(), stack));
        r.addProperty("slot", a.slot());
        r.add("item", JavaItems.json(stack));
    }

    private static void dialog(JavaBot b, ScreenAction.Dialog d, JsonObject r) {
        Holder<Dialog> holder = b.screens.dialog();
        if (holder == null) {
            throw new AgentError("NO_DIALOG", b.name() + " has no dialog open", "Wait for the plugin to show one (gui_read shows screen.dialog).");
        }
        Dialog dialog = holder.value();
        DialogModel.Button button = DialogModel.pick(dialog, d.button());
        r.addProperty("button", button.label());
        if (button.opens() != null) {
            b.screens.dialog(button.opens());
            r.addProperty("opened", "dialog");
            return;
        }
        Optional<ClickEvent> e = DialogModel.clickEvent(dialog, button, d.inputs());
        if (dialog.common().afterAction() == DialogAction.CLOSE) b.screens.dialog(null);
        e.ifPresent(ev -> r.add("action", b.screens.run(ev)));
    }

    private static void chatClick(JavaBot b, ScreenAction.ChatClick c, JsonObject r) {
        Message m = c.messageId() != null
                ? b.inbox().byId(c.messageId()).orElseThrow(() -> new AgentError("MESSAGE_NOT_FOUND", "No message " + c.messageId(),
                        "Use an id from bot_action messages; only recent messages are kept."))
                : b.inbox().newestClickable(c.match()).orElseThrow(() -> new AgentError("MESSAGE_NOT_FOUND",
                        "No clickable message contains " + c.match(), "Check bot_action messages for messages with clicks."));
        List<ClickEvent> events = b.chat.clicks(m.id());
        if (c.part() >= events.size()) {
            throw BotErrors.invalid("Message " + m.id() + " has " + events.size() + " clickable part(s); part is 0-based");
        }
        r.addProperty("message", m.id());
        r.addProperty("clicked", m.actions().get(c.part()).text());
        r.add("action", b.screens.run(events.get(c.part())));
    }

    static void requireMenu(JavaBot b) {
        if (!b.inventory.menuOpen()) throw BotErrors.noScreen(b.name());
    }

    private static void requireType(JavaBot b, String type, String what) {
        requireMenu(b);
        if (!type.equals(b.inventory.type())) {
            throw new AgentError("WRONG_SCREEN", b.name() + " does not have " + what + " open",
                    "Open it first (use the block, or the plugin's command).");
        }
    }
}
