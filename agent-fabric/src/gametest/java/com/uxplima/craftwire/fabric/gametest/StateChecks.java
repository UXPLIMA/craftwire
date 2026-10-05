package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

final class StateChecks {
    private StateChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("give @a minecraft:diamond 5");
        sp.getServer().runCommand("bossbar add craftwire:test \"Build Progress\"");
        sp.getServer().runCommand("bossbar set craftwire:test players @a");
        sp.getServer().runCommand("bossbar set craftwire:test value 40");
        sp.getServer().runCommand("title @a title \"Hello Title\"");
        ctx.waitTicks(10);

        JsonObject state = Calls.call(ctx, "player.state", "{}").getAsJsonObject();
        check(state.has("position") && state.getAsJsonObject("position").has("y"), "player.state needs position");
        check(state.get("dimension").getAsString().equals("minecraft:overworld"), "dimension: " + state.get("dimension"));
        boolean hasDiamond = state.getAsJsonArray("inventory").asList().stream()
                .anyMatch(e -> e.getAsJsonObject().get("id").getAsString().equals("minecraft:diamond")
                        && e.getAsJsonObject().get("count").getAsInt() == 5);
        check(hasDiamond, "inventory should contain 5 diamonds: " + state.get("inventory"));

        JsonObject hud = Calls.call(ctx, "hud.read", "{}").getAsJsonObject();
        check(hud.getAsJsonArray("bossbars").size() == 1, "one bossbar expected: " + hud);
        check(hud.getAsJsonArray("bossbars").get(0).getAsJsonObject().get("name").getAsString().equals("Build Progress"), "bossbar name");
        check("Hello Title".equals(hud.get("title").getAsString()), "title: " + hud.get("title"));

        JsonObject sent = Calls.call(ctx, "chat.send", "{\"text\":\"/time set noon\",\"command\":true}").getAsJsonObject();
        check(sent.get("sent").getAsBoolean(), "chat.send should report sent");
        check("INVALID_PARAMS".equals(Calls.error(ctx, "chat.send", "{\"text\":\"\",\"command\":false}").code()), "empty text must be INVALID_PARAMS");
        sp.getServer().runCommand("bossbar remove craftwire:test");
    }
}
