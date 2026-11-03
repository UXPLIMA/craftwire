package com.uxplima.craftwire.fabric.gui;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.Click;
import com.uxplima.craftwire.bot.gui.ClickPlan;
import com.uxplima.craftwire.bot.gui.ClickType;
import com.uxplima.craftwire.bot.gui.GuiStep;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.ItemMoves;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.handlers.GuiActionHandler;
import com.uxplima.craftwire.fabric.mixin.AbstractContainerScreenAccessor;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Slot input on the client: hover, the click vocabulary (with the old click, right_click, shift_click and
 * slot-to-slot drag kept as aliases) and the helpers move_item, transfer and equip planned by bot-core. Render thread
 * except {@link #helper}, which spreads its clicks over ticks.
 */
public final class SlotActions {
    public static final Set<String> CLICKS = Set.of("hover", "click", "right_click", "shift_click", "drag");
    public static final Set<String> HELPERS = Set.of("move_item", "transfer", "equip");

    private SlotActions() {}

    /** A click from the vocabulary, sent at once (a double click or a drag is several packets in one go, as with the mouse). */
    public static void click(Screen screen, String action, JsonObject p) {
        AbstractContainerScreen<?> acs = requireContainer(screen);
        List<Slot> slots = acs.getMenu().slots;
        if (action.equals("hover")) {
            Slot slot = slots.get(slotId(slots, Params.integer(p, "slot")));
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) acs;
            GuiActionHandler.moveMouseTo(Minecraft.getInstance(), acc.craftwire$getLeftPos() + slot.x + 8, acc.craftwire$getTopPos() + slot.y + 8);
            return;
        }
        if (action.equals("drag") && p.has("toSlot")) {
            // The old drag: carry the stack from slot to toSlot with two left clicks.
            int from = slotId(slots, Params.integer(p, "slot"));
            int to = slotId(slots, Params.integer(p, "toSlot"));
            ClickRunner.clicks(acs, List.of(RawClick.pickup(from, 0), RawClick.pickup(to, 0)));
            return;
        }
        Click c = parse(action, p);
        c.check(slots.size());
        ClickRunner.clicks(acs, ClickPlan.of(c));
    }

    private static Click parse(String action, JsonObject p) {
        return switch (action) {
            case "right_click" -> Click.of(ClickType.RIGHT, Params.integer(p, "slot"));
            case "shift_click" -> Click.of(ClickType.SHIFT, Params.integer(p, "slot"));
            case "drag" -> {
                JsonObject q = p.deepCopy();
                q.addProperty("click", "drag");
                yield Click.parse(q);
            }
            default -> Click.parse(p);
        };
    }

    /**
     * Plans a helper on the open menu (equip and move_item open the player's inventory when nothing is open, like
     * pressing E, and close it again), then runs it one click per tick. Returns what was done.
     */
    public static CompletableFuture<JsonObject> helper(String action, JsonObject p, ClientScheduler s) {
        return s.call(() -> plan(action, p)).thenCompose(plan -> ClickRunner.run(plan.steps(), plan.screen(), s).handle((v, err) -> {
            if (plan.opened()) s.call(() -> close(plan.screen()));
            if (err != null) throw err instanceof RuntimeException re ? re : new IllegalStateException(err);
            JsonObject r = new JsonObject();
            r.addProperty("action", action);
            r.addProperty("clicks", plan.steps().size());
            return r;
        }));
    }

    private record Plan(AbstractContainerScreen<?> screen, List<GuiStep> steps, boolean opened) {}

    private static Plan plan(String action, JsonObject p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) throw new AgentError("NOT_IN_WORLD", "The player is not in a world.", "Join a world or server first.");
        Screen open = mc.gui.screen();
        boolean opened = false;
        if (open == null && !action.equals("transfer")) {
            open = new InventoryScreen(mc.player);
            mc.gui.setScreen(open);
            opened = true;
        }
        AbstractContainerScreen<?> acs = requireContainer(open);
        try {
            return new Plan(acs, steps(action, p, acs), opened);
        } catch (RuntimeException e) {
            if (opened) close(acs);
            throw e;
        }
    }

    private static List<GuiStep> steps(String action, JsonObject p, AbstractContainerScreen<?> acs) {
        if (acs instanceof CreativeModeInventoryScreen) {
            throw new AgentError("CREATIVE_INVENTORY", "The helpers need the survival inventory layout",
                    "Close the creative inventory first (gui_action close), or use creative_set.");
        }
        GuiView view = ClientMenus.view(acs);
        return switch (action) {
            case "move_item" -> ItemMoves.moveItem(view, Params.integer(p, "from"), Params.integer(p, "to"), Params.optInt(p, "count").orElse(null));
            case "transfer" -> {
                String from = Params.optString(p, "from").orElse("container");
                if (!from.equals("container") && !from.equals("inventory")) throw BotErrors.invalid("from must be container or inventory");
                yield ItemMoves.transfer(view, from.equals("container"), Params.optString(p, "item").orElse(null),
                        Params.optInt(p, "count").orElse(null));
            }
            case "equip" -> ItemMoves.equip(view, Minecraft.getInstance().player.getInventory().getSelectedSlot(),
                    Params.optInt(p, "slot").orElse(null), Params.optString(p, "item").orElse(null), Params.optString(p, "to").orElse("hand"));
            default -> throw BotErrors.invalid("Unknown helper: " + action);
        };
    }

    private static Object close(AbstractContainerScreen<?> screen) {
        if (Minecraft.getInstance().gui.screen() == screen) screen.onClose();
        return null;
    }

    static AbstractContainerScreen<?> requireContainer(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?> acs) return acs;
        if (screen == null) {
            throw new AgentError("NO_SCREEN_OPEN", "No screen is open.",
                    "Open a menu first (e.g. chat {action:'command', text:'/menu'}), then wait_for {condition:'screen_open'}.");
        }
        throw new AgentError("NOT_A_CONTAINER", "The open screen (" + screen.getClass().getSimpleName() + ") has no item slots.",
                "Use click_widget for buttons; gui_read lists widgets.");
    }

    private static int slotId(List<Slot> slots, int id) {
        if (id < 0 || id >= slots.size()) throw BotErrors.slotOutOfRange(id, slots.size());
        return id;
    }
}
