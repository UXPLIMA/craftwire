package com.uxplima.craftwire.botrunner.handlers;

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
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.botrunner.bot.RunnerBots;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The menu verbs of protocol bots, on the bot model: gui_read, gui_click with the full vocabulary, the helpers
 * move_item, transfer and equip, and gui_close. Each kind turns the raw clicks into its own packets (container click
 * on Java, ItemStackRequest on Bedrock). Results carry the menu as it is after a short settle.
 */
final class RunnerGui {
    private static final Set<String> ACTIONS = Set.of("gui_read", "gui_click", "move_item", "transfer", "equip", "gui_close");

    private RunnerGui() {}

    static boolean handles(String action) {
        return ACTIONS.contains(action);
    }

    static CompletableFuture<JsonElement> run(String action, JsonObject p, RunnerBots bots, String name) {
        return switch (action) {
            case "gui_read" -> bots.on(name, b -> (JsonElement) b.gui().json());
            case "gui_click" -> {
                Click c = Click.parse(p);
                yield settled(p, bots, name, b -> {
                    GuiView v = b.gui();
                    c.check(v.slotCount());
                    for (RawClick raw : ClickPlan.of(c)) b.click(raw);
                    JsonObject r = new JsonObject();
                    r.addProperty("clicked", c.type().id());
                    if (c.slot() >= 0) r.addProperty("slot", c.slot());
                    return r;
                });
            }
            case "move_item" -> settled(p, bots, name, b -> steps(b, ItemMoves.moveItem(b.gui(), Params.integer(p, "from"),
                    Params.integer(p, "to"), Params.optInt(p, "count").orElse(null))));
            case "transfer" -> settled(p, bots, name, b -> {
                String from = Params.optString(p, "from").orElse("container");
                if (!from.equals("container") && !from.equals("inventory")) throw BotErrors.invalid("from must be container or inventory");
                GuiView v = b.gui();
                if (!v.open()) throw BotErrors.noScreen(b.name());
                return steps(b, ItemMoves.transfer(v, from.equals("container"), Params.optString(p, "item").orElse(null),
                        Params.optInt(p, "count").orElse(null)));
            });
            case "equip" -> settled(p, bots, name, b -> steps(b, ItemMoves.equip(b.gui(), b.state().heldSlot(),
                    Params.optInt(p, "slot").orElse(null), Params.optString(p, "item").orElse(null), Params.optString(p, "to").orElse("hand"))));
            case "gui_close" -> bots.on(name, b -> {
                if (!b.gui().open()) throw BotErrors.noScreen(b.name());
                b.closeScreen();
                JsonObject r = new JsonObject();
                r.addProperty("closed", "menu");
                return (JsonElement) r;
            });
            default -> CompletableFuture.failedFuture(BotErrors.invalid("Unknown menu action: " + action));
        };
    }

    /** Runs the work, waits `settleMs` (default 150) for the server to answer, then adds the menu as it is now. */
    private static CompletableFuture<JsonElement> settled(JsonObject p, RunnerBots bots, String name, Function<RunnerBot, JsonObject> work) {
        long settleMs = Params.clampedLong(p, "settleMs", 150L, 0L, 5000L);
        return bots.on(name, work)
                .thenCompose(v -> CompletableFuture.supplyAsync(() -> v, CompletableFuture.delayedExecutor(settleMs, TimeUnit.MILLISECONDS)))
                .thenCompose(v -> bots.on(name, b -> {
                    v.add("gui", b.gui().json());
                    return (JsonElement) v;
                }));
    }

    private static JsonObject steps(RunnerBot b, List<GuiStep> steps) {
        for (GuiStep s : steps) {
            switch (s) {
                case GuiStep.ClickStep c -> b.click(c.click());
                case GuiStep.HotbarStep h -> b.selectHotbar(h.slot());
            }
        }
        JsonObject r = new JsonObject();
        r.addProperty("clicks", steps.size());
        return r;
    }
}
