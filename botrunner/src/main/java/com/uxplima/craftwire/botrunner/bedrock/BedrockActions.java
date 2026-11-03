package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bedrock.packet.BedrockItem;
import com.uxplima.craftwire.bedrock.packet.LoginOut;
import com.uxplima.craftwire.bedrock.packet.PlayOut;
import com.uxplima.craftwire.bedrock.packet.StackRequest;
import com.uxplima.craftwire.bot.gui.ScreenAction;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.model.ClientSettings;
import com.uxplima.craftwire.bot.model.ItemInfo;
import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The bot_action actions of a Bedrock bot beyond the common ones, with the names Java bots use: hud_read,
 * resource_packs, settings, jump, sneak, sprint, select_hotbar, drop, swap_hands, use, attack, break_block,
 * respawn, entities, and dialog_answer for forms and NPC dialogues (plus form_close and server_settings). Actions
 * whose effect only the server knows report the bot's view after a short settle. Runner loop thread.
 */
final class BedrockActions {
    private static final long SETTLE_MS = 150;
    private static final List<String> FACES = List.of("down", "up", "north", "south", "west", "east");

    private BedrockActions() {}

    static Optional<CompletableFuture<JsonElement>> run(BedrockBot b, String action, JsonObject p) {
        if (ScreenAction.handles(action)) return Optional.of(screen(b, ScreenAction.parse(action, p)));
        CompletableFuture<JsonElement> f = switch (action) {
            case "hud_read" -> now(b.hud.json(System.currentTimeMillis()));
            case "resource_packs" -> now(b.packs.log().json());
            case "settings" -> now(settings(b, ClientSettings.parse(Params.object(p, "settings"))));
            case "jump" -> now(jump(b));
            case "sneak", "sprint" -> now(toggle(b, action, Params.bool(p, "on", true)));
            case "select_hotbar" -> {
                int slot = Params.integer(p, "slot");
                if (slot < 0 || slot > 8) throw BotErrors.invalid("slot must be 0-8");
                b.selectHotbar(slot);
                yield settled(b, new JsonObject(), BedrockActions::held, "held");
            }
            case "drop" -> drop(b, Params.bool(p, "all", false));
            case "swap_hands" -> swapHands(b);
            case "use" -> settled(b, BedrockInteract.use(b, p), bot -> bot.gui().json(), "gui");
            case "attack" -> now(BedrockInteract.attack(b, p));
            case "break_block" -> breakBlock(b, p);
            case "respawn" -> now(respawn(b));
            case "entities" -> now(entities(b, p));
            case "form_close" -> {
                b.closeForm();
                yield now(new JsonObject());
            }
            case "server_settings" -> {
                b.send(PlayOut.serverSettings());
                yield settled(b, new JsonObject(), bot -> bot.gui().json(), "gui");
            }
            default -> null;
        };
        return Optional.ofNullable(f);
    }

    private static CompletableFuture<JsonElement> screen(BedrockBot b, ScreenAction a) {
        if (!(a instanceof ScreenAction.Dialog d)) {
            throw new AgentError("UNSUPPORTED_FOR_KIND", "Bedrock bots have no " + a.getClass().getSimpleName().toLowerCase(Locale.ROOT)
                    + " screen action", "Bedrock servers use forms: gui_read shows screen.dialog, dialog_answer answers it.");
        }
        b.answerForm(d.button(), d.inputs());
        return settled(b, new JsonObject(), bot -> bot.gui().json(), "gui");
    }

    private static CompletableFuture<JsonElement> now(JsonObject r) {
        return CompletableFuture.completedFuture(r);
    }

    private static CompletableFuture<JsonElement> settled(BedrockBot b, JsonObject r, Function<BedrockBot, JsonElement> after, String key) {
        return CompletableFuture.supplyAsync(() -> r, CompletableFuture.delayedExecutor(SETTLE_MS, TimeUnit.MILLISECONDS))
                .thenCompose(x -> b.ctx.services().loop().call(() -> {
                    x.add(key, after.apply(b));
                    return (JsonElement) x;
                }));
    }

    /** Bedrock has no client options packet; the view distance is the only setting a server hears, through RequestChunkRadius. */
    private static JsonObject settings(BedrockBot b, ClientSettings s) {
        if (s.viewDistance() > 0) b.send(LoginOut.requestChunkRadius(s.viewDistance()));
        JsonObject r = s.json();
        r.addProperty("note", "Bedrock servers hear only the view distance; locale and skin were sent at login");
        return r;
    }

    private static JsonObject jump(BedrockBot b) {
        boolean jumped = b.body.jumpNow();
        JsonObject r = new JsonObject();
        r.addProperty("jumped", jumped);
        if (!jumped) r.addProperty("reason", "not on the ground");
        return r;
    }

    private static JsonObject toggle(BedrockBot b, String what, boolean on) {
        if (what.equals("sneak")) b.body.sneak(on);
        else b.body.sprinting(on);
        JsonObject r = new JsonObject();
        r.addProperty("sneaking", b.body.sneaking());
        r.addProperty("sprinting", b.body.sprintingNow());
        return r;
    }

    /** The Q key: a Drop stack request from the held hotbar slot. */
    private static CompletableFuture<JsonElement> drop(BedrockBot b, boolean all) {
        int slot = b.life.heldSlot();
        BedrockItem before = b.inventory.hotbar(slot);
        if (before.empty()) throw new AgentError("NOTHING_HELD", b.name() + " holds nothing", "select_hotbar a slot that has an item.");
        StackRequest.Slot from = BedrockSlots.inventory(b.inventory, slot);
        b.stackRequest(List.of(new StackRequest.Drop(all ? before.count() : 1, from)), from, from);
        JsonObject r = new JsonObject();
        ItemInfo info = b.items.info(before);
        if (info != null) r.add("before", info.json());
        return settled(b, r, BedrockActions::held, "held");
    }

    private static CompletableFuture<JsonElement> swapHands(BedrockBot b) {
        StackRequest.Slot main = BedrockSlots.inventory(b.inventory, b.life.heldSlot());
        StackRequest.Slot off = BedrockSlots.offhand(b.inventory);
        b.stackRequest(List.of(new StackRequest.Swap(main, off)), main, off);
        return settled(b, new JsonObject(), BedrockActions::hands, "hands");
    }

    private static JsonElement held(BedrockBot b) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", b.life.heldSlot());
        ItemInfo info = b.items.info(b.inventory.hotbar(b.life.heldSlot()));
        if (info != null) o.add("item", info.json());
        return o;
    }

    private static JsonElement hands(BedrockBot b) {
        JsonObject o = new JsonObject();
        ItemInfo main = b.items.info(b.inventory.hotbar(b.life.heldSlot()));
        ItemInfo off = b.items.info(b.inventory.offhand[0]);
        if (main != null) o.add("mainHand", main.json());
        if (off != null) o.add("offHand", off.json());
        return o;
    }

    private static CompletableFuture<JsonElement> breakBlock(BedrockBot b, JsonObject p) {
        BlockPoint at = BlockPoint.parse(Params.object(p, "block"));
        Integer face = Params.optString(p, "face").map(f -> FACES.indexOf(f.toLowerCase(Locale.ROOT))).filter(i -> i >= 0).orElse(null);
        return BedrockDig.start(b, at, face, Params.clampedLong(p, "timeoutMs", 30_000L, 500L, 120_000L)).thenApply(r -> (JsonElement) r);
    }

    private static JsonObject respawn(BedrockBot b) {
        JsonObject r = new JsonObject();
        r.addProperty("wasDead", b.life.dead());
        if (b.life.dead()) BedrockSpawn.requestRespawn(b);
        return r;
    }

    private static JsonObject entities(BedrockBot b, JsonObject p) {
        String type = Params.optString(p, "type").map(t -> t.contains(":") ? t : "minecraft:" + t).orElse(null);
        double range = Params.optNumber(p, "range").orElse(32.0);
        JsonObject r = new JsonObject();
        r.add("entities", b.entities.nearJson(b.position(), type, range, Params.clampedInt(p, "limit", 50, 1, 500)));
        return r;
    }
}
