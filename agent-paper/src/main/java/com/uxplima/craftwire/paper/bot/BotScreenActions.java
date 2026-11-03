package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.ScreenAction;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.mc.DialogModel;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The special screens (anvil, sign, book, merchant, menu buttons, beacon, recipe book, creative, dialogs, clickable
 * chat), each through the server's handler for the packet a client sends. Results add the screen after a settle.
 */
final class BotScreenActions {
    private static final long SETTLE_MS = 150;

    private BotScreenActions() {}

    static CompletableFuture<JsonElement> run(ScreenAction a, BotManager bots, String name) {
        return bots.on(name, b -> {
                    b.settle();
                    return act(b, a);
                })
                .thenCompose(r -> CompletableFuture.supplyAsync(() -> r, CompletableFuture.delayedExecutor(SETTLE_MS, TimeUnit.MILLISECONDS)))
                .thenCompose(r -> bots.on(name, b -> {
                    r.add("gui", BotJson.gui(b, false).json());
                    return (JsonElement) r;
                }));
    }

    private static JsonObject act(Bot b, ScreenAction a) {
        JsonObject r = new JsonObject();
        switch (a) {
            case ScreenAction.Rename n -> {
                if (!(b.player().containerMenu instanceof AnvilMenu)) throw notOpen(b, "an anvil");
                b.listener().handleRenameItem(new ServerboundRenameItemPacket(n.text()));
                r.addProperty("renamed", n.text());
            }
            case ScreenAction.Sign s -> sign(b, s, r);
            case ScreenAction.Book k -> {
                int slot = k.slot() >= 0 ? k.slot() : b.bukkit().getInventory().getHeldItemSlot();
                b.listener().handleEditBook(new ServerboundEditBookPacket(slot, k.pages(), Optional.ofNullable(k.title())));
                b.link().screens.book(false);
                r.addProperty("pages", k.pages().size());
                r.addProperty("signed", k.title() != null);
            }
            case ScreenAction.Trade t -> {
                if (!(b.player().containerMenu instanceof MerchantMenu)) throw notOpen(b, "a merchant");
                b.listener().handleSelectTrade(new ServerboundSelectTradePacket(t.index()));
                r.addProperty("selected", t.index());
            }
            case ScreenAction.Button btn -> {
                BotGuiActions.requireMenu(b);
                int container = b.player().containerMenu.containerId;
                b.listener().handleContainerButtonClick(new ServerboundContainerButtonClickPacket(container, btn.id()));
                r.addProperty("button", btn.id());
            }
            case ScreenAction.Beacon be -> BotRecipes.beacon(b, be, r);
            case ScreenAction.Recipe rc -> BotRecipes.place(b, rc, r);
            case ScreenAction.Creative c -> BotRecipes.creative(b, c, r);
            case ScreenAction.Dialog d -> dialog(b, d, r);
            case ScreenAction.ChatClick c -> chatClick(b, c, r);
        }
        return r;
    }

    /** With no editor open for that sign, right-clicks it first: the server then lets the bot edit it, as for a player. */
    private static void sign(Bot b, ScreenAction.Sign s, JsonObject r) {
        ServerCompat.SignTarget open = b.link().screens.sign();
        BlockPos pos;
        if (s.block() != null) {
            pos = new BlockPos(s.block().x(), s.block().y(), s.block().z());
            if (open == null || !open.pos().equals(pos)) {
                Vec3 hit = Vec3.atCenterOf(pos);
                BotInteract.lookAt(b, hit);
                b.listener().handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
                        new BlockHitResult(hit, Direction.UP, pos, false), b.nextSequence()));
            }
        } else if (open != null) {
            pos = open.pos();
        } else {
            throw new AgentError("NO_SIGN_EDITOR", "No sign editor is open", "Pass block {x,y,z} of the sign, or right-click it first (use).");
        }
        ServerCompat.get().signUpdate(b.listener(), pos, s.front(), s.lines());
        b.link().screens.sign(null);
        r.addProperty("signed", pos.toShortString());
        r.addProperty("side", s.front() ? "front" : "back");
    }

    private static void dialog(Bot b, ScreenAction.Dialog d, JsonObject r) {
        Holder<Dialog> holder = b.link().screens.dialog();
        if (holder == null) {
            throw new AgentError("NO_DIALOG", b.name() + " has no dialog open", "Wait for the plugin to show one (gui_read shows screen.dialog).");
        }
        Dialog dialog = holder.value();
        DialogModel.Button button = DialogModel.pick(dialog, d.button());
        r.addProperty("button", button.label());
        if (button.opens() != null) {
            b.link().screens.dialog(button.opens());
            r.addProperty("opened", "dialog");
            return;
        }
        Optional<ClickEvent> e = DialogModel.clickEvent(dialog, button, d.inputs());
        if (dialog.common().afterAction() == DialogAction.CLOSE) b.link().screens.dialog(null);
        e.ifPresent(ev -> r.add("action", BotClickEvents.run(b, ev)));
    }

    /** Escape on a dialog: its cancel action runs, if it has one, and it closes. */
    static void cancelDialog(Bot b) {
        Holder<Dialog> holder = b.link().screens.dialog();
        b.link().screens.dialog(null);
        if (holder == null) return;
        Dialog dialog = holder.value();
        DialogModel.Button cancel = new DialogModel.Button("cancel", dialog.onCancel(), null);
        DialogModel.clickEvent(dialog, cancel, Map.of()).ifPresent(ev -> BotClickEvents.run(b, ev));
    }

    private static void chatClick(Bot b, ScreenAction.ChatClick c, JsonObject r) {
        Message m = c.messageId() != null
                ? b.inbox().byId(c.messageId()).orElseThrow(() -> new AgentError("MESSAGE_NOT_FOUND", "No message " + c.messageId(),
                        "Use an id from bot_action messages; only recent messages are kept."))
                : b.inbox().newestClickable(c.match()).orElseThrow(() -> new AgentError("MESSAGE_NOT_FOUND",
                        "No clickable message contains " + c.match(), "Check bot_action messages for messages with clicks."));
        List<ClickEvent> events = b.link().clicks(m.id());
        if (c.part() >= events.size()) {
            throw BotErrors.invalid("Message " + m.id() + " has " + events.size() + " clickable part(s); part is 0-based");
        }
        r.addProperty("message", m.id());
        r.addProperty("clicked", m.actions().get(c.part()).text());
        r.add("action", BotClickEvents.run(b, events.get(c.part())));
    }

    private static AgentError notOpen(Bot b, String what) {
        return new AgentError("WRONG_SCREEN", b.name() + " does not have " + what + " open",
                "Open it first (use the block, or the plugin's command).");
    }
}
