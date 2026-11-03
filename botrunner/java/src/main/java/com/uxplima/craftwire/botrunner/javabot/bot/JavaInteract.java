package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.botrunner.world.TrackedEntity;
import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * use and attack for a Java bot, as the packets a client sends when a player right- or left-clicks: on a block, on
 * an entity the bot can see, or into the air. Reach is checked against the vanilla survival ranges first, so a
 * refused interaction says why instead of the server silently ignoring it. Runner loop thread.
 */
final class JavaInteract {
    private static final double BLOCK_REACH = 4.5;
    private static final double ENTITY_REACH = 3.0;

    private JavaInteract() {}

    static JsonObject use(JavaBot b, JsonObject p) {
        InteractionHand hand = Params.optString(p, "hand").orElse("main").equals("off") ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        JsonObject r = new JsonObject();
        if (p.has("block")) {
            JsonObject at = Params.object(p, "block");
            BlockPos pos = new BlockPos(Params.integer(at, "x"), Params.integer(at, "y"), Params.integer(at, "z"));
            Direction face = Direction.byName(Params.optString(p, "face").orElse("up").toLowerCase(Locale.ROOT));
            if (face == null) throw BotErrors.invalid("face must be up, down, north, south, east or west");
            Vec3 c = Vec3.atCenterOf(pos);
            reach(b, c.x, c.y, c.z, BLOCK_REACH);
            b.screens.useOn(pos, face, hand);
            r.addProperty("used", "block");
        } else if (p.has("entity")) {
            TrackedEntity e = entity(b, Params.string(p, "entity"));
            double half = height(e) / 2;
            reach(b, e.x(), e.y() + half, e.z(), ENTITY_REACH);
            b.screens.lookAt(e.x(), e.y() + half, e.z());
            b.send(new ServerboundInteractPacket((int) e.id(), hand, new Vec3(0, half, 0), b.body.sneaking()));
            b.ctx.compat().swing(hand == InteractionHand.MAIN_HAND).forEach(b::send);
            r.addProperty("used", "entity");
        } else {
            b.send(new ServerboundUseItemPacket(hand, b.nextSequence(), b.body.yaw(), b.body.pitch()));
            b.ctx.compat().swing(hand == InteractionHand.MAIN_HAND).forEach(b::send);
            r.addProperty("used", "air");
        }
        return r;
    }

    static JsonObject attack(JavaBot b, JsonObject p) {
        TrackedEntity e = p.has("entity") ? entity(b, Params.string(p, "entity")) : nearest(b, Params.string(p, "type"));
        double half = height(e) / 2;
        reach(b, e.x(), e.y() + half, e.z(), ENTITY_REACH);
        b.screens.lookAt(e.x(), e.y() + half, e.z());
        b.send(new ServerboundAttackPacket((int) e.id()));
        b.swing();
        JsonObject t = new JsonObject();
        t.addProperty("uuid", e.uuid().toString());
        t.addProperty("type", e.type());
        if (e.name() != null) t.addProperty("name", e.name());
        JsonObject r = new JsonObject();
        r.add("target", t);
        return r;
    }

    private static TrackedEntity nearest(JavaBot b, String type) {
        String t = type.toLowerCase(Locale.ROOT);
        String id = t.contains(":") ? t : "minecraft:" + t;
        List<TrackedEntity> near = b.world.near(b.position(), id, ENTITY_REACH + 1.5);
        near.removeIf(e -> e.id() == b.life.entityId());
        if (near.isEmpty()) {
            throw new AgentError("ENTITY_NOT_FOUND", "No " + id + " within reach of " + b.name(),
                    "move_to the target first, or pass entity (a UUID from bot_action entities).");
        }
        return near.getFirst();
    }

    private static TrackedEntity entity(JavaBot b, String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException bad) {
            throw BotErrors.invalid("entity must be a UUID");
        }
        TrackedEntity e = b.world.entity(id);
        if (e == null) {
            throw new AgentError("ENTITY_NOT_FOUND", b.name() + " does not see entity " + uuid,
                    "bot_action entities lists what the bot sees; move_to closer if it is out of tracking range.");
        }
        return e;
    }

    private static double height(TrackedEntity e) {
        Identifier key = Identifier.tryParse(e.type());
        EntityType<?> type = key == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(key).orElse(null);
        return type == null ? 1.0 : type.getHeight();
    }

    private static void reach(JavaBot b, double x, double y, double z, double range) {
        double d = b.screens.eyeDistance(x, y, z);
        if (d > range + 0.5) {
            throw new AgentError("OUT_OF_REACH", String.format(Locale.ROOT, "Target is %.1f blocks away; reach is %.1f", d, range),
                    "move_to closer first.");
        }
    }
}
