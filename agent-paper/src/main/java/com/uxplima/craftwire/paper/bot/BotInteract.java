package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.move.Steering;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.compat.ServerCompat;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** use and attack, sent through the server's own packet handlers so plugins see real-client interaction events. */
final class BotInteract {
    private BotInteract() {}

    static CompletableFuture<JsonElement> run(String action, JsonObject p, BotManager bots, String name) {
        return bots.on(name, b -> {
            // A /tp in the same tick as the action would otherwise make the server ignore it.
            b.settle();
            return action.equals("use") ? use(b, p) : attack(b, p);
        });
    }

    private static JsonElement use(Bot b, JsonObject p) {
        ServerPlayer sp = b.player();
        InteractionHand hand = Params.optString(p, "hand").orElse("main").equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        JsonObject r = new JsonObject();
        if (p.has("block")) {
            JsonObject at = Params.object(p, "block");
            BlockPos pos = new BlockPos(Params.integer(at, "x"), Params.integer(at, "y"), Params.integer(at, "z"));
            Direction face = Direction.byName(Params.optString(p, "face").orElse("up").toLowerCase(Locale.ROOT));
            if (face == null) throw BotErrors.invalid("face must be up, down, north, south, east or west");
            Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            reach(sp, hit, sp.blockInteractionRange());
            lookAt(b, hit);
            b.listener().handleUseItemOn(new ServerboundUseItemOnPacket(hand, new BlockHitResult(hit, face, pos, false), b.nextSequence()));
            r.addProperty("used", "block");
        } else if (p.has("entity")) {
            Entity target = entity(sp, Params.string(p, "entity"));
            Vec3 center = target.position().add(0, target.getBbHeight() / 2, 0);
            reach(sp, center, sp.entityInteractionRange());
            lookAt(b, center);
            b.listener().handleInteract(new ServerboundInteractPacket(target.getId(), hand, new Vec3(0, target.getBbHeight() / 2, 0), false));
            r.addProperty("used", "entity");
        } else {
            b.listener().handleUseItem(new ServerboundUseItemPacket(hand, b.nextSequence(), sp.getYRot(), sp.getXRot()));
            r.addProperty("used", "air");
        }
        ServerCompat.get().swingAfterInteraction(b.listener(), hand);
        return r;
    }

    private static JsonElement attack(Bot b, JsonObject p) {
        ServerPlayer sp = b.player();
        Entity target = p.has("entity") ? entity(sp, Params.string(p, "entity")) : nearest(b, sp, Params.string(p, "type"));
        Vec3 center = target.position().add(0, target.getBbHeight() / 2, 0);
        reach(sp, center, sp.entityInteractionRange());
        lookAt(b, center);
        double before = target instanceof LivingEntity l ? l.getHealth() : 0;
        b.listener().handleAttack(new ServerboundAttackPacket(target.getId()));
        ServerCompat.get().swingAfterInteraction(b.listener(), InteractionHand.MAIN_HAND);
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

    private static Entity nearest(Bot b, ServerPlayer sp, String type) {
        String t = type.toLowerCase(Locale.ROOT);
        String id = t.contains(":") ? t : "minecraft:" + t;
        double range = sp.entityInteractionRange();
        return sp.level().getEntities(sp, sp.getBoundingBox().inflate(range), e -> EntityType.getKey(e.getType()).toString().equals(id))
                .stream().min(Comparator.comparingDouble(sp::distanceToSqr))
                .orElseThrow(() -> new AgentError("ENTITY_NOT_FOUND", "No " + id + " within reach of " + b.name(),
                        "move_to the target first, or pass entity (a UUID from world_query entities)."));
    }

    private static Entity entity(ServerPlayer sp, String uuid) {
        Entity e;
        try {
            e = sp.level().getEntity(UUID.fromString(uuid));
        } catch (IllegalArgumentException bad) {
            throw BotErrors.invalid("entity must be a UUID");
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

    static void lookAt(Bot b, Vec3 point) {
        Vec3 eye = b.player().getEyePosition();
        b.look(Steering.yaw(point.x - eye.x, point.z - eye.z), Steering.pitch(point.x - eye.x, point.y - eye.y, point.z - eye.z));
    }
}
