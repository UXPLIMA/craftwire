package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bedrock.packet.BedrockItem;
import com.uxplima.craftwire.bedrock.packet.ItemUse;
import com.uxplima.craftwire.bedrock.packet.PlayOut;
import com.uxplima.craftwire.bedrock.packet.TransactionOut;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.botrunner.world.TrackedEntity;
import com.uxplima.craftwire.core.AgentError;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * use and attack for a Bedrock bot, as the InventoryTransaction packets a client sends on a tap: on a block
 * (UseItem, click block), into the air (click air), or on an entity (UseItemOnEntity: interact or attack). Reach is
 * checked first so a refused interaction says why. Runner loop thread.
 */
final class BedrockInteract {
    private static final double BLOCK_REACH = 6.0;
    private static final double ENTITY_REACH = 3.0;
    private static final List<String> FACES = List.of("down", "up", "north", "south", "west", "east");

    private BedrockInteract() {}

    static JsonObject use(BedrockBot b, JsonObject p) {
        JsonObject r = new JsonObject();
        if (p.has("block")) {
            BlockPoint at = BlockPoint.parse(Params.object(p, "block"));
            int face = FACES.indexOf(Params.optString(p, "face").orElse("up").toLowerCase(Locale.ROOT));
            if (face < 0) throw BotErrors.invalid("face must be up, down, north, south, east or west");
            Position c = at.center();
            reach(b, c.x(), c.y(), c.z(), BLOCK_REACH);
            useOn(b, at, face);
            r.addProperty("used", "block");
        } else if (p.has("entity")) {
            TrackedEntity e = entity(b, Params.string(p, "entity"));
            reach(b, e.x(), e.y() + 0.9, e.z(), ENTITY_REACH);
            lookAt(b, e.x(), e.y() + 0.9, e.z());
            onEntity(b, e, TransactionOut.ENTITY_INTERACT);
            r.addProperty("used", "entity");
        } else {
            BedrockItem held = b.inventory.hotbar(b.life.heldSlot());
            Position pos = b.position();
            b.send(TransactionOut.useItem(new ItemUse(ItemUse.CLICK_AIR, 0, 0, 0, 255, b.life.heldSlot(), held, (float) pos.x(),
                    (float) (pos.y() + BedrockEntities.EYE), (float) pos.z(), 0, 0, 0, 0), b.items.shieldId()));
            b.swing();
            r.addProperty("used", "air");
        }
        return r;
    }

    /** A click on a block face: opens containers and doors, places the held block against it. */
    static void useOn(BedrockBot b, BlockPoint at, int face) {
        Position c = at.center();
        lookAt(b, c.x(), c.y(), c.z());
        int state = b.world.state(at.x(), at.y(), at.z());
        int runtime = state < 0 || b.world.palette() == null ? 0 : Math.max(0, b.world.palette().runtimeId(state));
        Position pos = b.position();
        float[] click = clickPoint(face);
        b.send(TransactionOut.useItem(new ItemUse(ItemUse.CLICK_BLOCK, at.x(), at.y(), at.z(), face, b.life.heldSlot(),
                b.inventory.hotbar(b.life.heldSlot()), (float) pos.x(), (float) (pos.y() + BedrockEntities.EYE), (float) pos.z(),
                click[0], click[1], click[2], runtime), b.items.shieldId()));
        b.swing();
    }

    static JsonObject attack(BedrockBot b, JsonObject p) {
        TrackedEntity e = p.has("entity") ? entity(b, Params.string(p, "entity")) : nearest(b, Params.string(p, "type"));
        reach(b, e.x(), e.y() + 0.9, e.z(), ENTITY_REACH);
        lookAt(b, e.x(), e.y() + 0.9, e.z());
        b.swing();
        onEntity(b, e, TransactionOut.ENTITY_ATTACK);
        JsonObject t = new JsonObject();
        t.addProperty("uuid", e.uuid().toString());
        t.addProperty("type", e.type());
        if (e.name() != null) t.addProperty("name", e.name());
        JsonObject r = new JsonObject();
        r.add("target", t);
        return r;
    }

    private static void onEntity(BedrockBot b, TrackedEntity e, int action) {
        Position pos = b.position();
        float[] botPos = {(float) pos.x(), (float) (pos.y() + BedrockEntities.EYE), (float) pos.z()};
        float[] clickPos = {(float) e.x(), (float) (e.y() + 0.9), (float) e.z()};
        b.send(PlayOut.interact(4, e.id()));
        b.send(TransactionOut.onEntity(e.id(), action, b.life.heldSlot(), b.inventory.hotbar(b.life.heldSlot()), botPos, clickPos,
                b.items.shieldId(), b.version()));
    }

    private static TrackedEntity nearest(BedrockBot b, String type) {
        String t = type.toLowerCase(Locale.ROOT);
        String id = t.contains(":") ? t : "minecraft:" + t;
        List<TrackedEntity> near = b.entities.near(b.position(), id, ENTITY_REACH + 1.5);
        if (near.isEmpty()) {
            throw new AgentError("ENTITY_NOT_FOUND", "No " + id + " within reach of " + b.name(),
                    "move_to the target first, or pass entity (a UUID from bot_action entities).");
        }
        return near.getFirst();
    }

    private static TrackedEntity entity(BedrockBot b, String uuid) {
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException bad) {
            throw BotErrors.invalid("entity must be a UUID");
        }
        TrackedEntity e = b.entities.entity(id);
        if (e == null) {
            throw new AgentError("ENTITY_NOT_FOUND", b.name() + " does not see entity " + uuid,
                    "bot_action entities lists what the bot sees; move_to closer if it is out of tracking range.");
        }
        return e;
    }

    /** The point on a face the tap hits, relative to the block: the face's centre. */
    private static float[] clickPoint(int face) {
        return switch (face) {
            case 0 -> new float[] {0.5f, 0f, 0.5f};
            case 1 -> new float[] {0.5f, 1f, 0.5f};
            case 2 -> new float[] {0.5f, 0.5f, 0f};
            case 3 -> new float[] {0.5f, 0.5f, 1f};
            case 4 -> new float[] {0f, 0.5f, 0.5f};
            default -> new float[] {1f, 0.5f, 0.5f};
        };
    }

    static double eyeDistance(BedrockBot b, double x, double y, double z) {
        Position p = b.position();
        double dx = x - p.x();
        double dy = y - (p.y() + BedrockEntities.EYE);
        double dz = z - p.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Turns the body to look at a point, as the client's camera does before a tap. */
    static void lookAt(BedrockBot b, double x, double y, double z) {
        Position p = b.position();
        double dx = x - p.x();
        double dy = y - (p.y() + BedrockEntities.EYE);
        double dz = z - p.z();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        b.look(yaw, pitch);
    }

    /** The block face that points at the bot's eyes (Bedrock face ids: down, up, north, south, west, east). */
    static int faceTowards(BedrockBot b, Position c) {
        Position p = b.position();
        double dx = p.x() - c.x();
        double dy = p.y() + BedrockEntities.EYE - c.y();
        double dz = p.z() - c.z();
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? 1 : 0;
        if (ax >= az) return dx > 0 ? 5 : 4;
        return dz > 0 ? 3 : 2;
    }

    private static void reach(BedrockBot b, double x, double y, double z, double range) {
        double d = eyeDistance(b, x, y, z);
        if (d > range + 0.5) {
            throw new AgentError("OUT_OF_REACH", String.format(Locale.ROOT, "Target is %.1f blocks away; reach is %.1f", d, range),
                    "move_to closer first.");
        }
    }
}
