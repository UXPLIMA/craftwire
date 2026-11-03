package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.ScreenAction;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.model.ClientSettings;
import com.uxplima.craftwire.core.AgentError;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;

/**
 * The Java bot's own bot_action verbs (the common ones live in the runner's RunnerActions): HUD, resource packs,
 * settings, jump, sneak, sprint, hotbar, drop, swap hands, use, attack, break_block, respawn, entities, recipes and
 * the special screens. Actions whose effect only the server knows report the bot's view after a short settle.
 * Runner loop thread.
 */
final class JavaActions {
    private static final long SETTLE_MS = 150;

    private JavaActions() {}

    static Optional<CompletableFuture<JsonElement>> run(JavaBot b, String action, JsonObject p) {
        if (ScreenAction.handles(action)) {
            JsonObject r = JavaScreenActions.act(b, ScreenAction.parse(action, p));
            return Optional.of(settled(b, r, bot -> bot.gui().json(), "gui"));
        }
        CompletableFuture<JsonElement> f = switch (action) {
            case "hud_read" -> now(hud(b));
            case "resource_packs" -> now(b.packs.log().json());
            case "settings" -> now(settings(b, ClientSettings.parse(Params.object(p, "settings"))));
            case "jump" -> now(jump(b));
            case "sneak", "sprint" -> now(toggle(b, action, Params.bool(p, "on", true)));
            case "select_hotbar" -> {
                int slot = Params.integer(p, "slot");
                if (slot < 0 || slot > 8) throw BotErrors.invalid("slot must be 0-8");
                b.selectHotbar(slot);
                yield settled(b, new JsonObject(), JavaActions::held, "held");
            }
            case "drop" -> drop(b, Params.bool(p, "all", false));
            case "swap_hands" -> {
                playerAction(b, ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND);
                yield settled(b, new JsonObject(), JavaActions::hands, "hands");
            }
            case "use" -> settled(b, JavaInteract.use(b, p), bot -> bot.gui().json(), "gui");
            case "attack" -> now(JavaInteract.attack(b, p));
            case "break_block" -> breakBlock(b, p);
            case "respawn" -> {
                b.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                yield now(dead(b));
            }
            case "entities" -> now(entities(b, p));
            case "recipes" -> now(b.recipes.json(b.registries, Params.clampedInt(p, "limit", 100, 1, 2000)));
            default -> null;
        };
        return Optional.ofNullable(f);
    }

    private static CompletableFuture<JsonElement> now(JsonObject r) {
        return CompletableFuture.completedFuture(r);
    }

    /** Waits for the server to answer, then adds what `after` reads from the bot under `key`, on the loop. */
    private static CompletableFuture<JsonElement> settled(JavaBot b, JsonObject r, Function<JavaBot, JsonElement> after, String key) {
        return CompletableFuture.supplyAsync(() -> r, CompletableFuture.delayedExecutor(SETTLE_MS, TimeUnit.MILLISECONDS))
                .thenCompose(x -> b.ctx.services().loop().call(() -> {
                    x.add(key, after.apply(b));
                    return (JsonElement) x;
                }));
    }

    /** ClientInformation and brand sent again mid-game, as a client does when its options change. */
    private static JsonObject settings(JavaBot b, ClientSettings s) {
        b.sendAny(new ServerboundClientInformationPacket(JavaJoin.information(s, b.spec.profile())));
        b.sendAny(new ServerboundCustomPayloadPacket(new BrandPayload(s.brand())));
        return s.json();
    }

    private static JsonObject hud(JavaBot b) {
        JsonObject r = b.hud.json(b.name(), System.currentTimeMillis());
        if (!b.tabWatched) {
            b.tabWatched = true;
            r.addProperty("tabNote", "light bots copy the tab list from their first hud_read on; players who were online before may be missing");
        }
        return r;
    }

    private static JsonObject jump(JavaBot b) {
        boolean jumped = b.body.jumpNow();
        JsonObject r = new JsonObject();
        r.addProperty("jumped", jumped);
        if (!jumped) r.addProperty("reason", "not on the ground");
        return r;
    }

    /** Sneak through the input keys (PlayerToggleSneakEvent), sprint through the player command (PlayerToggleSprintEvent). */
    private static JsonObject toggle(JavaBot b, String what, boolean on) {
        if (what.equals("sneak")) b.moves.sneak(on);
        else b.body.sprinting(on);
        JsonObject r = new JsonObject();
        r.addProperty("sneaking", b.body.sneaking());
        r.addProperty("sprinting", b.body.sprintingNow());
        return r;
    }

    private static CompletableFuture<JsonElement> drop(JavaBot b, boolean all) {
        ItemStack before = b.inventory.item(36 + b.life.heldSlot());
        if (before.isEmpty()) {
            throw new AgentError("NOTHING_HELD", b.name() + " holds nothing", "select_hotbar a slot that has an item.");
        }
        playerAction(b, all ? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS : ServerboundPlayerActionPacket.Action.DROP_ITEM);
        JsonObject r = new JsonObject();
        r.add("before", JavaItems.json(before));
        return settled(b, r, JavaActions::held, "held");
    }

    private static void playerAction(JavaBot b, ServerboundPlayerActionPacket.Action a) {
        b.send(new ServerboundPlayerActionPacket(a, BlockPos.ZERO, Direction.DOWN));
    }

    private static JsonElement held(JavaBot b) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", b.life.heldSlot());
        ItemStack held = b.inventory.item(36 + b.life.heldSlot());
        if (!held.isEmpty()) o.add("item", JavaItems.json(held));
        return o;
    }

    private static JsonElement hands(JavaBot b) {
        JsonObject o = new JsonObject();
        ItemStack main = b.inventory.item(36 + b.life.heldSlot());
        ItemStack off = b.inventory.item(45);
        if (!main.isEmpty()) o.add("mainHand", JavaItems.json(main));
        if (!off.isEmpty()) o.add("offHand", JavaItems.json(off));
        return o;
    }

    private static CompletableFuture<JsonElement> breakBlock(JavaBot b, JsonObject p) {
        JsonObject at = Params.object(p, "block");
        Direction face = Params.optString(p, "face").map(f -> Direction.byName(f.toLowerCase(Locale.ROOT))).orElse(null);
        BlockPos pos = new BlockPos(Params.integer(at, "x"), Params.integer(at, "y"), Params.integer(at, "z"));
        return JavaDig.start(b, pos, face, Params.clampedLong(p, "timeoutMs", 30_000L, 500L, 120_000L)).thenApply(r -> (JsonElement) r);
    }

    private static JsonObject dead(JavaBot b) {
        JsonObject r = new JsonObject();
        r.addProperty("wasDead", b.life.dead());
        return r;
    }

    private static JsonObject entities(JavaBot b, JsonObject p) {
        String type = Params.optString(p, "type").map(t -> t.contains(":") ? t : "minecraft:" + t).orElse(null);
        double range = Params.optNumber(p, "range").orElse(32.0);
        JsonObject r = new JsonObject();
        r.add("entities", b.world.nearJson(b.position(), type, range, Params.clampedInt(p, "limit", 50, 1, 500)));
        return r;
    }
}
