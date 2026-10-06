package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
            case "chat" -> sync.global(() -> {
                bots.get(name).bukkit().chat(Args.string(p, "text"));
                JsonObject r = new JsonObject();
                r.addProperty("sent", true);
                return (JsonElement) r;
            });
            case "command" -> command(p, bots, sync, name);
            case "messages" -> sync.global(() -> {
                long since = Args.optLong(p, "since").orElse(0L);
                int limit = Math.clamp(Args.optInt(p, "limit").orElse(50), 1, 200);
                JsonObject r = new JsonObject();
                r.add("messages", BotJson.messages(bots.get(name).inbox().since(since, limit)));
                return (JsonElement) r;
            });
            case "look" -> sync.global(() -> look(bots.get(name), p));
            case "move_to" -> sync.global(() -> bots.get(name).moveTo(
                            Args.number(p, "x"), Args.number(p, "y"), Args.number(p, "z"),
                            p.has("tolerance") ? Args.number(p, "tolerance") : 0.5,
                            Args.bool(p, "sprint", false),
                            Math.clamp(Args.optLong(p, "timeoutMs").orElse(10_000L), 500L, 120_000L)))
                    .thenCompose(f -> f).thenApply(r -> (JsonElement) r);
            case "state" -> sync.global(() -> (JsonElement) BotJson.state(bots.get(name)));
            case "hud_read" -> sync.global(() -> (JsonElement) bots.get(name).hud());
            case "give" -> sync.global(() -> give(bots.get(name), p));
            case "select_hotbar" -> sync.global(() -> selectHotbar(bots.get(name), Args.integer(p, "slot")));
            default -> BotGuiActions.run(action, p, bots, sync, name);
        };
    }

    private record Ran(Bot bot, boolean success) {}

    private static CompletableFuture<JsonElement> command(JsonObject p, BotManager bots, Sync sync, String name) {
        String raw = Args.string(p, "command").strip();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        long collectMs = Math.clamp(Args.optLong(p, "collectMs").orElse(300L), 0L, 5000L);
        long started = System.currentTimeMillis();
        return sync.global(() -> {
                    Bot b = bots.get(name);
                    return new Ran(b, b.bukkit().performCommand(command));
                })
                .thenCompose(r -> CompletableFuture.supplyAsync(() -> r, CompletableFuture.delayedExecutor(collectMs, TimeUnit.MILLISECONDS)))
                .thenApply(r -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("command", command);
                    o.addProperty("success", r.success());
                    o.add("messages", BotJson.messages(r.bot().inbox().since(started, 200)));
                    return o;
                });
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
