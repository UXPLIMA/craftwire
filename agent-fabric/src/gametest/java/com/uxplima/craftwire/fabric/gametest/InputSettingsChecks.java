package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

final class InputSettingsChecks {
    private InputSettingsChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("gamemode survival @a");
        ctx.waitTicks(2);

        JsonObject r = Calls.call(ctx, "input", "{\"hotbar\":3}").getAsJsonObject();
        check(r.get("selectedSlot").getAsInt() == 3, "hotbar 3: " + r);

        float yawBefore = ctx.computeOnClient(mc -> mc.player.getYRot());
        Calls.call(ctx, "input", "{\"look\":{\"yaw\":90,\"pitch\":10}}");
        check(Math.abs(ctx.computeOnClient(mc -> mc.player.getYRot()) - (yawBefore + 90)) < 0.01, "yaw +90");

        Calls.call(ctx, "input", "{\"keys\":[\"sneak\"],\"mode\":\"hold\"}");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.player.isShiftKeyDown()), "sneak held");
        Calls.call(ctx, "input", "{\"keys\":[\"sneak\"],\"mode\":\"release\"}");
        ctx.waitTicks(2);
        check(!ctx.computeOnClient(mc -> mc.player.isShiftKeyDown()), "sneak released");

        Calls.call(ctx, "input", "{\"keys\":[\"inventory\"]}");
        ctx.waitTicks(3);
        check(Calls.call(ctx, "gui.read", "{}").getAsJsonObject().get("open").getAsBoolean(), "inventory key opens a screen");
        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");

        var err = Calls.error(ctx, "input", "{\"keys\":[\"teleport\"]}");
        check("INVALID_PARAMS".equals(err.code()) && err.hint().contains("forward"), "unknown key lists valid names");

        JsonObject settings = Calls.call(ctx, "client.settings", "{\"fov\":90,\"hideHud\":true}").getAsJsonObject();
        check(settings.get("fov").getAsInt() == 90, "fov 90: " + settings);
        check(settings.get("hideHud").getAsBoolean(), "hud hidden");
        JsonObject back = Calls.call(ctx, "client.settings", "{\"fov\":70,\"hideHud\":false}").getAsJsonObject();
        check(!back.get("hideHud").getAsBoolean() && back.get("fov").getAsInt() == 70, "restored: " + back);
        check(Calls.call(ctx, "client.settings", "{}").getAsJsonObject().has("windowSize"), "read-only call returns values");
    }
}
