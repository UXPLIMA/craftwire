package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.ItemJson;
import com.uxplima.craftwire.fabric.Params;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class PlayerStateHandler {
    private PlayerStateHandler() {}

    static JsonElement read() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) throw Params.notInWorld();
        JsonObject o = new JsonObject();
        o.add("position", vec(p.position()));
        o.addProperty("yaw", p.getYRot());
        o.addProperty("pitch", p.getXRot());
        o.addProperty("health", p.getHealth());
        o.addProperty("gameMode", mc.gameMode == null ? "unknown" : mc.gameMode.getPlayerMode().getName());
        o.addProperty("dimension", mc.level.dimension().identifier().toString());
        o.addProperty("selectedSlot", p.getInventory().getSelectedSlot() + 1);
        o.add("held", ItemJson.of(p.getMainHandItem(), false));
        JsonArray inv = new JsonArray();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            JsonObject item = ItemJson.of(s, false);
            item.addProperty("slot", i);
            inv.add(item);
        }
        o.add("inventory", inv);
        o.add("target", target(mc));
        return o;
    }

    private static JsonElement target(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            JsonObject t = new JsonObject();
            t.addProperty("type", "block");
            BlockPos pos = b.getBlockPos();
            t.add("pos", vec(Vec3.atLowerCornerOf(pos)));
            t.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString());
            return t;
        }
        if (hit instanceof EntityHitResult e && hit.getType() == HitResult.Type.ENTITY) {
            Entity en = e.getEntity();
            JsonObject t = new JsonObject();
            t.addProperty("type", "entity");
            t.addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).toString());
            t.addProperty("name", en.getName().getString());
            t.addProperty("uuid", en.getUUID().toString());
            return t;
        }
        return JsonNull.INSTANCE;
    }

    static JsonObject vec(Vec3 v) {
        JsonObject o = new JsonObject();
        o.addProperty("x", v.x);
        o.addProperty("y", v.y);
        o.addProperty("z", v.z);
        return o;
    }
}
