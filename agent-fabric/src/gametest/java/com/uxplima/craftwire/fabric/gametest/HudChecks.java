package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;

/** Scoreboards the way plugins build them: lines made of team prefix/suffix, hidden numbers, tab header/footer. */
final class HudChecks {
    private HudChecks() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        String[] setup = {
            "scoreboard objectives add cw dummy \"Lobby\"",
            "scoreboard objectives setdisplay sidebar cw",
            "scoreboard objectives modify cw numberformat blank",
            "team add line1",
            "team modify line1 prefix \"Coins: \"",
            "team modify line1 suffix \" 42\"",
            "team join line1 x",
            "scoreboard players set x cw 2",
            "scoreboard players set Gold cw 1",
            "scoreboard players set #hidden cw 5",
            "scoreboard players set Gold2 cw 1",
            "scoreboard players set gold1 cw 1",
            "scoreboard objectives add kills dummy",
            "scoreboard objectives setdisplay list kills",
            "scoreboard players set @a kills 7",
            "scoreboard objectives add hp dummy \"HP\"",
            "scoreboard objectives setdisplay below_name hp",
            "scoreboard players set @a hp 20",
            "team add red",
            "team modify red prefix \"[R] \"",
            "team join red @a",
        };
        for (String c : setup) sp.getServer().runCommand(c);
        sp.getServer().runOnServer(s -> s.getPlayerList().broadcastAll(
                new ClientboundTabListPacket(Component.literal("§6My Server"), Component.literal("play.example.net"))));
        ctx.waitTicks(10);

        JsonObject hud = Calls.call(ctx, "hud.read", "{}").getAsJsonObject();
        JsonObject sidebar = hud.getAsJsonObject("sidebar");
        check("Lobby".equals(sidebar.get("title").getAsString()), "sidebar title: " + sidebar);
        JsonArray rows = sidebar.getAsJsonArray("entries");
        check(rows.size() == 4, "hidden (#) lines are left out: " + rows);
        JsonObject first = rows.get(0).getAsJsonObject();
        check("Coins: x 42".equals(first.get("name").getAsString()), "line text includes team prefix/suffix: " + first);
        check(first.get("value").getAsInt() == 2, "value: " + first);
        check(first.get("shown").isJsonNull(), "numberformat blank hides the number: " + first);
        // Equal scores are ordered by name, case-insensitively, like the game draws them.
        check("Gold".equals(rows.get(1).getAsJsonObject().get("name").getAsString()), "order: " + rows);
        check("gold1".equals(rows.get(2).getAsJsonObject().get("name").getAsString()), "order: " + rows);
        check("Gold2".equals(rows.get(3).getAsJsonObject().get("name").getAsString()), "order: " + rows);

        JsonObject tab = hud.getAsJsonObject("tab");
        check("My Server".equals(tab.get("header").getAsString()), "header without colour codes: " + tab);
        check("play.example.net".equals(tab.get("footer").getAsString()), "footer: " + tab);
        JsonObject me = tab.getAsJsonArray("players").get(0).getAsJsonObject();
        check(me.get("display").getAsString().startsWith("[R] "), "tab name includes the team prefix: " + me);
        check(me.get("display").getAsString().endsWith(me.get("name").getAsString()), "display ends with the name: " + me);
        check(me.has("ping") && "survival".equals(me.get("gameMode").getAsString()), "ping and game mode: " + me);
        check(me.get("score").getAsInt() == 7, "list objective score: " + me);
        check(hud.getAsJsonArray("tabList").get(0).getAsString().startsWith("[R] "), "tabList keeps strings: " + hud.get("tabList"));

        JsonObject below = hud.getAsJsonObject("belowName");
        check("HP".equals(below.get("title").getAsString()), "below-name title: " + below);
        JsonObject hp = below.getAsJsonArray("entries").get(0).getAsJsonObject();
        check(hp.get("name").getAsString().equals(me.get("name").getAsString()) && hp.get("value").getAsInt() == 20, "below-name score: " + below);

        String[] cleanup = {
            "scoreboard objectives remove cw", "scoreboard objectives remove kills", "scoreboard objectives remove hp",
            "team remove line1", "team remove red",
        };
        for (String c : cleanup) sp.getServer().runCommand(c);
        sp.getServer().runOnServer(s -> s.getPlayerList().broadcastAll(new ClientboundTabListPacket(Component.empty(), Component.empty())));
        ctx.waitTicks(5);
        JsonObject after = Calls.call(ctx, "hud.read", "{}").getAsJsonObject();
        check(after.get("sidebar").isJsonNull() && after.get("belowName").isJsonNull(), "no sidebar/below-name after removal: " + after);
        check(after.getAsJsonObject("tab").get("header").isJsonNull(), "empty header reads as null: " + after.get("tab"));
    }
}
