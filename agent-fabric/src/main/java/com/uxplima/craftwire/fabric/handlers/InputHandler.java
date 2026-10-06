package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.Params;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

final class InputHandler {
    private InputHandler() {}

    private static final Map<String, Function<Options, KeyMapping>> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("forward", o -> o.keyUp);
        KEYS.put("back", o -> o.keyDown);
        KEYS.put("left", o -> o.keyLeft);
        KEYS.put("right", o -> o.keyRight);
        KEYS.put("jump", o -> o.keyJump);
        KEYS.put("sneak", o -> o.keyShift);
        KEYS.put("sprint", o -> o.keySprint);
        KEYS.put("attack", o -> o.keyAttack);
        KEYS.put("use", o -> o.keyUse);
        KEYS.put("pick", o -> o.keyPickItem);
        KEYS.put("drop", o -> o.keyDrop);
        KEYS.put("inventory", o -> o.keyInventory);
        KEYS.put("swap_hands", o -> o.keySwapOffhand);
        KEYS.put("chat", o -> o.keyChat);
        KEYS.put("command", o -> o.keyCommand);
        KEYS.put("player_list", o -> o.keyPlayerList);
        KEYS.put("perspective", o -> o.keyTogglePerspective);
        KEYS.put("hide_gui", o -> o.keyToggleGui);
        KEYS.put("screenshot", o -> o.keyScreenshot);
    }

    static JsonElement handle(JsonObject p, ClientScheduler s) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) throw Params.notInWorld();
        String mode = Params.optString(p, "mode").orElse("press");
        int durationMs = Params.optInt(p, "durationMs").orElse(0);

        List<InputConstants.Key> keys = new ArrayList<>();
        JsonArray names = p.has("keys") && p.get("keys").isJsonArray() ? p.getAsJsonArray("keys") : new JsonArray();
        for (JsonElement n : names) {
            Function<Options, KeyMapping> f = KEYS.get(n.getAsString());
            if (f == null) {
                throw new AgentError("INVALID_PARAMS", "Unknown key \"" + n.getAsString() + "\".", "Valid keys: " + String.join(", ", KEYS.keySet()));
            }
            keys.add(KeyMappingHelper.getBoundKeyOf(f.apply(mc.options)));
        }
        for (InputConstants.Key key : keys) {
            switch (mode) {
                case "hold" -> KeyMapping.set(key, true);
                case "release" -> KeyMapping.set(key, false);
                case "press" -> {
                    KeyMapping.click(key);
                    if (durationMs > 0) {
                        KeyMapping.set(key, true);
                        s.delay((int) Math.ceil(durationMs / 50.0)).thenRun(() -> KeyMapping.set(key, false));
                    }
                }
                default -> throw Params.invalid("mode must be press, hold or release");
            }
        }

        if (p.has("look") && p.get("look").isJsonObject()) {
            JsonObject look = p.getAsJsonObject("look");
            if (look.has("yaw")) player.setYRot(player.getYRot() + look.get("yaw").getAsFloat());
            if (look.has("pitch")) player.setXRot(Math.clamp(player.getXRot() + look.get("pitch").getAsFloat(), -90f, 90f));   // JDK, not Mth: Mth.clamp(FFF) is gone in 26.4
        }
        Params.optInt(p, "hotbar").ifPresent(h -> {
            if (h < 1 || h > 9) throw Params.invalid("hotbar must be 1-9");
            player.getInventory().setSelectedSlot(h - 1);
        });

        JsonObject o = new JsonObject();
        o.add("keys", names);
        o.addProperty("mode", mode);
        o.addProperty("yaw", player.getYRot());
        o.addProperty("pitch", player.getXRot());
        o.addProperty("selectedSlot", player.getInventory().getSelectedSlot() + 1);
        return o;
    }
}
