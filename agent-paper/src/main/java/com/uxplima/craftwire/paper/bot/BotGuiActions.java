package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.Click;
import com.uxplima.craftwire.bot.gui.ClickPlan;
import com.uxplima.craftwire.bot.gui.GuiStep;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.ItemMoves;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Menu verbs: gui_read, gui_click with the full click vocabulary, gui_close and the helpers move_item, transfer and
 * equip. Clicks go through the server's container click handler, so InventoryClickEvent and InventoryDragEvent
 * fire as for a real client; results include the menu as it is after a short settle.
 */
final class BotGuiActions {
    private BotGuiActions() {}

    static CompletableFuture<JsonElement> run(String action, JsonObject p, BotManager bots, String name) {
        boolean inventory = Params.bool(p, "inventory", false);
        return switch (action) {
            case "gui_read" -> bots.on(name, b -> (JsonElement) BotJson.gui(b, inventory).json());
            case "gui_click" -> {
                Click c = Click.parse(p);
                yield settled(p, bots, name, inventory, b -> {
                    GuiView v = view(b, inventory);
                    c.check(v.slotCount());
                    for (RawClick raw : ClickPlan.of(c)) b.click(raw);
                    JsonObject r = new JsonObject();
                    r.addProperty("clicked", c.type().id());
                    if (c.slot() >= 0) r.addProperty("slot", c.slot());
                    return r;
                });
            }
            case "move_item" -> settled(p, bots, name, inventory, b -> steps(b, ItemMoves.moveItem(view(b, inventory),
                    Params.integer(p, "from"), Params.integer(p, "to"), Params.optInt(p, "count").orElse(null))));
            case "transfer" -> settled(p, bots, name, false, b -> {
                String from = Params.optString(p, "from").orElse("container");
                if (!from.equals("container") && !from.equals("inventory")) throw BotErrors.invalid("from must be container or inventory");
                requireMenu(b);
                return steps(b, ItemMoves.transfer(BotJson.gui(b, false), from.equals("container"),
                        Params.optString(p, "item").orElse(null), Params.optInt(p, "count").orElse(null)));
            });
            case "equip" -> settled(p, bots, name, true, b -> steps(b, ItemMoves.equip(BotJson.gui(b, true),
                    b.bukkit().getInventory().getHeldItemSlot(), Params.optInt(p, "slot").orElse(null),
                    Params.optString(p, "item").orElse(null), Params.optString(p, "to").orElse("hand"))));
            case "gui_close" -> bots.on(name, BotGuiActions::close);
            default -> CompletableFuture.failedFuture(BotErrors.invalid("Unknown bot action: " + action));
        };
    }

    /** Runs the work, waits `settleMs` (default 150) for plugins to react, then adds the menu as it is now. */
    private static CompletableFuture<JsonElement> settled(JsonObject p, BotManager bots, String name, boolean inventory,
            Function<Bot, JsonObject> work) {
        long settleMs = Params.clampedLong(p, "settleMs", 150L, 0L, 5000L);
        return bots.on(name, work)
                .thenCompose(v -> CompletableFuture.supplyAsync(() -> v, CompletableFuture.delayedExecutor(settleMs, TimeUnit.MILLISECONDS)))
                .thenCompose(v -> bots.on(name, b -> {
                    v.add("gui", BotJson.gui(b, inventory).json());
                    return (JsonElement) v;
                }));
    }

    /** The menu a click addresses: the open menu, or with `inventory` the bot's own inventory (container 0). */
    private static GuiView view(Bot b, boolean inventory) {
        if (!inventory) requireMenu(b);
        return BotJson.gui(b, true);
    }

    private static JsonObject steps(Bot b, List<GuiStep> steps) {
        for (GuiStep s : steps) {
            switch (s) {
                case GuiStep.ClickStep c -> b.click(c.click());
                case GuiStep.HotbarStep h -> BotInventoryActions.selectHotbar(b, h.slot());
            }
        }
        JsonObject r = new JsonObject();
        r.addProperty("clicks", steps.size());
        return r;
    }

    /** Closes whatever is open: a dialog (running its cancel action, as Escape does), a sign editor, a book or a menu. */
    private static JsonElement close(Bot b) {
        BotScreens screens = b.link().screens;
        JsonObject r = new JsonObject();
        if (screens.dialog() != null) {
            BotScreenActions.cancelDialog(b);
            r.addProperty("closed", "dialog");
            return r;
        }
        if (screens.sign() != null || screens.book()) {
            r.addProperty("closed", screens.sign() != null ? "sign" : "book");
            screens.sign(null);
            screens.book(false);
            return r;
        }
        requireMenu(b);
        b.closeScreen();
        r.addProperty("closed", "menu");
        return r;
    }

    static void requireMenu(Bot b) {
        if (!BotJson.menuOpen(b.bukkit().getOpenInventory())) throw BotErrors.noScreen(b.name());
    }
}
