package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** GUI, use and attack verbs, sent through the server's own packet handlers so plugins see real-client events. */
final class BotGuiActions {
    private BotGuiActions() {}

    static CompletableFuture<JsonElement> run(String action, JsonObject p, BotManager bots, Sync sync, String name) {
        return switch (action) {
            case "gui_read" -> sync.global(() -> (JsonElement) BotJson.gui(bots.get(name), Args.bool(p, "inventory", false)));
            case "gui_click" -> {
                long settleMs = Math.clamp(Args.optLong(p, "settleMs").orElse(150L), 0L, 5000L);
                String click = Args.optString(p, "click").orElse("left");
                boolean inventory = Args.bool(p, "inventory", false);
                yield sync.global(() -> click(bots.get(name), Args.integer(p, "slot"), click, inventory))
                        .thenCompose(v -> CompletableFuture.supplyAsync(() -> v, CompletableFuture.delayedExecutor(settleMs, TimeUnit.MILLISECONDS)))
                        .thenCompose(v -> sync.global(() -> {
                            JsonObject r = new JsonObject();
                            r.addProperty("clicked", v);
                            r.add("gui", BotJson.gui(bots.get(name), inventory));
                            return (JsonElement) r;
                        }));
            }
            case "gui_close" -> sync.global(() -> close(bots.get(name)));
            case "use" -> sync.global(() -> use(settled(bots.get(name)), p));
            case "attack" -> sync.global(() -> attack(settled(bots.get(name)), p));
            default -> CompletableFuture.failedFuture(Args.invalid("Unknown bot action: " + action));
        };
    }

    /** A /tp in the same tick as the action would otherwise make the server ignore it. */
    private static Bot settled(Bot b) {
        b.settle();
        return b;
    }

    private static int click(Bot b, int slot, String click, boolean inventory) {
        if (!click.equals("left") && !click.equals("right") && !click.equals("shift")) throw Args.invalid("click must be left, right or shift");
        // With no menu open, containerMenu is the bot's own inventory (container 0), as with E on a client.
        if (!inventory) requireMenu(b);
        AbstractContainerMenu menu = b.player().containerMenu;
        if (slot < 0 || slot >= menu.slots.size()) {
            throw new AgentError("SLOT_OUT_OF_RANGE", "Slot " + slot + " is outside 0.." + (menu.slots.size() - 1),
                    "Use a slot number from gui_read.");
        }
        ContainerInput input = click.equals("shift") ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
        byte button = (byte) (click.equals("right") ? 1 : 0);
        // Empty predicted-change map: the server applies the click and resyncs the (absent) client afterwards.
        b.listener().handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) slot, button, input,
                new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
        return slot;
    }

    private static JsonElement close(Bot b) {
        requireMenu(b);
        b.listener().handleContainerClose(new ServerboundContainerClosePacket(b.player().containerMenu.containerId));
        JsonObject r = new JsonObject();
        r.addProperty("closed", true);
        return r;
    }

    private static void requireMenu(Bot b) {
        if (!BotJson.menuOpen(b.bukkit().getOpenInventory())) {
            throw new AgentError("NO_SCREEN_OPEN", b.name() + " has no menu open",
                    "Open one first, e.g. bot_action {action:'command'} with the plugin's menu command, or pass inventory:true for the bot's own inventory.");
        }
    }

    private static JsonElement use(Bot b, JsonObject p) {
        ServerPlayer sp = b.player();
        InteractionHand hand = Args.optString(p, "hand").orElse("main").equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        JsonObject r = new JsonObject();
        if (p.has("block")) {
            JsonObject at = Args.object(p, "block");
            BlockPos pos = new BlockPos(Args.integer(at, "x"), Args.integer(at, "y"), Args.integer(at, "z"));
            Direction face = Direction.byName(Args.optString(p, "face").orElse("up").toLowerCase(Locale.ROOT));
            if (face == null) throw Args.invalid("face must be up, down, north, south, east or west");
            Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            reach(sp, hit, sp.blockInteractionRange());
            lookAt(sp, hit);
            b.listener().handleUseItemOn(new ServerboundUseItemOnPacket(hand, new BlockHitResult(hit, face, pos, false), b.nextSequence()));
            r.addProperty("used", "block");
        } else if (p.has("entity")) {
            Entity target = entity(sp, Args.string(p, "entity"));
            Vec3 center = target.position().add(0, target.getBbHeight() / 2, 0);
            reach(sp, center, sp.entityInteractionRange());
            lookAt(sp, center);
            b.listener().handleInteract(new ServerboundInteractPacket(target.getId(), hand, new Vec3(0, target.getBbHeight() / 2, 0), false));
            r.addProperty("used", "entity");
        } else {
            b.listener().handleUseItem(new ServerboundUseItemPacket(hand, b.nextSequence(), sp.getYRot(), sp.getXRot()));
            r.addProperty("used", "air");
        }
        b.listener().handleAnimate(new ServerboundSwingPacket(hand));
        return r;
    }

    private static JsonElement attack(Bot b, JsonObject p) {
        ServerPlayer sp = b.player();
        Entity target;
        if (p.has("entity")) {
            target = entity(sp, Args.string(p, "entity"));
        } else {
            String type = Args.string(p, "type").toLowerCase(Locale.ROOT);
            String id = type.contains(":") ? type : "minecraft:" + type;
            double range = sp.entityInteractionRange();
            target = sp.level().getEntities(sp, sp.getBoundingBox().inflate(range),
                            e -> EntityType.getKey(e.getType()).toString().equals(id))
                    .stream().min(Comparator.comparingDouble(sp::distanceToSqr))
                    .orElseThrow(() -> new AgentError("ENTITY_NOT_FOUND", "No " + id + " within reach of " + b.name(),
                            "move_to the target first, or pass entity (a UUID from world_query entities)."));
        }
        Vec3 center = target.position().add(0, target.getBbHeight() / 2, 0);
        reach(sp, center, sp.entityInteractionRange());
        lookAt(sp, center);
        double before = target instanceof LivingEntity l ? l.getHealth() : 0;
        b.listener().handleAttack(new ServerboundAttackPacket(target.getId()));
        b.listener().handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        JsonObject t = new JsonObject();
        t.addProperty("uuid", target.getUUID().toString());
        t.addProperty("type", EntityType.getKey(target.getType()).toString());
        JsonObject r = new JsonObject();
        r.add("target", t);
        if (target instanceof LivingEntity l) {
            r.addProperty("healthBefore", before);
            r.addProperty("healthAfter", l.getHealth());
        }
        return r;
    }

    private static Entity entity(ServerPlayer sp, String uuid) {
        Entity e;
        try {
            e = sp.level().getEntity(UUID.fromString(uuid));
        } catch (IllegalArgumentException bad) {
            throw Args.invalid("entity must be a UUID");
        }
        if (e == null) {
            throw new AgentError("ENTITY_NOT_FOUND", "No entity " + uuid + " in " + sp.level().dimension().identifier(),
                    "Use world_query {action:'entities'} for UUIDs.");
        }
        return e;
    }

    private static void reach(ServerPlayer sp, Vec3 point, double range) {
        double d = sp.getEyePosition().distanceTo(point);
        if (d > range + 0.5) {
            throw new AgentError("OUT_OF_REACH", String.format(Locale.ROOT, "Target is %.1f blocks away; reach is %.1f", d, range),
                    "move_to closer first.");
        }
    }

    private static void lookAt(ServerPlayer sp, Vec3 point) {
        Vec3 eye = sp.getEyePosition();
        BotActions.face(sp, Steering.yaw(point.x - eye.x, point.z - eye.z), Steering.pitch(point.x - eye.x, point.y - eye.y, point.z - eye.z));
    }
}
