package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import com.uxplima.craftwire.paper.bot.path.Pathfinder;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** The bot.action verbs. Each runs its game work on the server thread through `sync`. */
public final class BotActions {
    private BotActions() {}

    public static CompletableFuture<JsonElement> run(JsonObject p, BotManager bots, Sync sync) {
        String action = Args.string(p, "action");
        String name = Args.string(p, "bot");
        return switch (action) {
            case "chat" -> bots.on(name, b -> {
                b.bukkit().chat(Args.string(p, "text"));
                JsonObject r = new JsonObject();
                r.addProperty("sent", true);
                return (JsonElement) r;
            });
            case "command" -> command(p, bots, sync, name);
            case "messages" -> bots.on(name, b -> {
                long since = Args.optLong(p, "since").orElse(0L);
                int limit = Math.clamp(Args.optInt(p, "limit").orElse(50), 1, 200);
                JsonObject r = new JsonObject();
                r.add("messages", BotJson.messages(b.inbox().since(since, limit)));
                return (JsonElement) r;
            });
            case "look" -> bots.on(name, b -> look(b, p));
            case "move_to" -> bots.on(name, b -> b.moveTo(
                            Args.number(p, "x"), Args.number(p, "y"), Args.number(p, "z"),
                            p.has("tolerance") ? Args.number(p, "tolerance") : 0.5,
                            Args.bool(p, "sprint", false),
                            Math.clamp(Args.optLong(p, "timeoutMs").orElse(30_000L), 500L, 120_000L),
                            Args.bool(p, "path", true),
                            new Pathfinder.Options(Math.clamp(Args.optInt(p, "maxFall").orElse(3), 0, 10),
                                    Args.bool(p, "openDoors", true), 40_000),
                            Args.bool(p, "partial", false)))
                    .thenCompose(f -> f).thenApply(r -> (JsonElement) r);
            case "break_block" -> bots.on(name, b -> {
                        JsonObject at = Args.object(p, "block");
                        Direction face = Args.optString(p, "face").map(f -> Direction.byName(f.toLowerCase(Locale.ROOT))).orElse(null);
                        return b.breakBlock(bots.plugin(), new BlockPos(Args.integer(at, "x"), Args.integer(at, "y"), Args.integer(at, "z")),
                                face, Math.clamp(Args.optLong(p, "timeoutMs").orElse(30_000L), 500L, 120_000L));
                    })
                    .thenCompose(f -> f).thenApply(r -> (JsonElement) r);
            case "jump" -> bots.on(name, b -> {
                boolean grounded = b.player().onGround();
                b.jump();
                JsonObject r = new JsonObject();
                r.addProperty("jumped", grounded);
                if (!grounded) r.addProperty("reason", "not on the ground");
                return (JsonElement) r;
            });
            case "sneak", "sprint" -> bots.on(name, b -> {
                boolean on = Args.bool(p, "on", true);
                if (action.equals("sneak")) b.sneak(on);
                else b.sprint(on);
                JsonObject r = new JsonObject();
                r.addProperty("sneaking", b.bukkit().isSneaking());
                r.addProperty("sprinting", b.bukkit().isSprinting());
                return (JsonElement) r;
            });
            case "drop" -> bots.on(name, b -> {
                ItemStack before = b.bukkit().getInventory().getItemInMainHand().clone();
                if (before.getType().isAir()) {
                    throw new AgentError("NOTHING_HELD", b.name() + " holds nothing", "give the bot an item, or select_hotbar a slot that has one.");
                }
                b.drop(Args.bool(p, "all", false));
                ItemStack after = b.bukkit().getInventory().getItemInMainHand();
                JsonObject r = new JsonObject();
                int dropped = before.getAmount() - (after.isSimilar(before) ? after.getAmount() : 0);
                JsonObject item = BotJson.item(before);
                item.addProperty("count", dropped);
                r.add("dropped", dropped > 0 ? item : null);
                if (dropped == 0) r.addProperty("cancelled", true);
                if (!after.getType().isAir()) r.add("held", BotJson.item(after));
                return (JsonElement) r;
            });
            case "swap_hands" -> bots.on(name, b -> {
                b.swapHands();
                JsonObject r = new JsonObject();
                ItemStack main = b.bukkit().getInventory().getItemInMainHand();
                ItemStack off = b.bukkit().getInventory().getItemInOffHand();
                if (!main.getType().isAir()) r.add("mainHand", BotJson.item(main));
                if (!off.getType().isAir()) r.add("offHand", BotJson.item(off));
                return (JsonElement) r;
            });
            case "state" -> bots.on(name, b -> (JsonElement) BotJson.state(b));
            case "hud_read" -> bots.on(name, b -> (JsonElement) b.hud());
            case "give" -> bots.on(name, b -> give(b, p));
            case "select_hotbar" -> bots.on(name, b -> selectHotbar(b, Args.integer(p, "slot")));
            default -> BotGuiActions.run(action, p, bots, sync, name);
        };
    }

    private record Ran(Bot bot, boolean known, CommandWatch watch) {}

    /**
     * Sends the command the way a client does (the chat-command packet), so PlayerCommandPreprocessEvent fires and
     * command blockers, aliases and loggers see bot commands. success = the command exists and no plugin cancelled it.
     */
    private static CompletableFuture<JsonElement> command(JsonObject p, BotManager bots, Sync sync, String name) {
        String raw = Args.string(p, "command").strip();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        long collectMs = Math.clamp(Args.optLong(p, "collectMs").orElse(300L), 0L, 5000L);
        long started = System.currentTimeMillis();
        return bots.on(name, b -> {
                    String label = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
                    boolean known = Bukkit.getCommandMap().getCommand(label) != null;
                    CommandWatch watch = CommandWatch.start(bots.plugin(), b.uuid());
                    b.listener().handleChatCommand(new ServerboundChatCommandPacket(command));
                    return new Ran(b, known, watch);
                })
                .thenCompose(r -> CompletableFuture.supplyAsync(() -> r, CompletableFuture.delayedExecutor(Math.max(collectMs, 50L), TimeUnit.MILLISECONDS)))
                .thenCompose(r -> bots.on(name, b -> {
                    r.watch().stop();
                    Boolean cancelled = r.watch().cancelled();
                    JsonObject o = new JsonObject();
                    o.addProperty("command", command);
                    o.addProperty("success", r.known() && Boolean.FALSE.equals(cancelled));
                    if (Boolean.TRUE.equals(cancelled)) o.addProperty("cancelled", true);
                    if (!r.known()) o.addProperty("unknown", true);
                    String finalText = r.watch().message();
                    if (finalText != null && !finalText.equals("/" + command)) o.addProperty("rewrittenTo", finalText);
                    o.add("messages", BotJson.messages(r.bot().inbox().since(started, 200)));
                    return (JsonElement) o;
                }).whenComplete((o, t) -> r.watch().stop()));
    }

    private static JsonElement look(Bot b, JsonObject p) {
        ServerPlayer sp = b.player();
        float yaw;
        float pitch;
        if (p.has("x")) {
            Vec3 eye = sp.getEyePosition();
            double dx = Args.number(p, "x") - eye.x;
            double dy = Args.number(p, "y") - eye.y;
            double dz = Args.number(p, "z") - eye.z;
            yaw = Steering.yaw(dx, dz);
            pitch = Steering.pitch(dx, dy, dz);
        } else {
            yaw = (float) Args.number(p, "yaw");
            pitch = (float) Math.clamp(Args.number(p, "pitch"), -90, 90);
        }
        face(sp, yaw, pitch);
        JsonObject r = new JsonObject();
        r.addProperty("yaw", BotJson.round(yaw));
        r.addProperty("pitch", BotJson.round(pitch));
        return r;
    }

    static void face(ServerPlayer sp, float yaw, float pitch) {
        sp.setYRot(yaw);
        sp.setXRot(pitch);
        sp.setYHeadRot(yaw);
        sp.setYBodyRot(yaw);
    }

    private static JsonElement give(Bot b, JsonObject p) {
        String id = Args.string(p, "item");
        ItemStack stack;
        try {
            stack = Bukkit.getItemFactory().createItemStack(id);
        } catch (IllegalArgumentException e) {
            throw new AgentError("INVALID_PARAMS", "Unknown item: " + id,
                    "Use an item id like diamond_sword, optionally with components: diamond_sword[enchantments={sharpness:5}].");
        }
        int count = Math.clamp(Args.optInt(p, "count").orElse(1), 1, 2304);
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

    private static JsonElement selectHotbar(Bot b, int slot) {
        if (slot < 0 || slot > 8) throw Args.invalid("slot must be 0-8");
        // Through the packet handler so PlayerItemHeldEvent fires as for a real client.
        b.listener().handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
        JsonObject r = new JsonObject();
        r.addProperty("slot", b.bukkit().getInventory().getHeldItemSlot());
        ItemStack held = b.bukkit().getInventory().getItemInMainHand();
        if (!held.getType().isAir()) r.add("held", BotJson.item(held));
        return r;
    }
}
