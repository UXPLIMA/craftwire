package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.mixin.BossHealthOverlayAccessor;
import com.uxplima.craftwire.fabric.mixin.HudAccessor;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

final class HudReadHandler {
    private HudReadHandler() {}

    static JsonElement read() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) throw Params.notInWorld();
        HudAccessor hud = (HudAccessor) mc.gui.hud;
        JsonObject o = new JsonObject();

        JsonArray bars = new JsonArray();
        for (LerpingBossEvent e : ((BossHealthOverlayAccessor) hud.craftwire$getBossOverlay()).craftwire$getEvents().values()) {
            JsonObject b = new JsonObject();
            b.addProperty("name", e.getName().getString());
            b.addProperty("progress", e.getProgress());
            b.addProperty("color", e.getColor().name().toLowerCase());
            bars.add(b);
        }
        o.add("bossbars", bars);
        o.add("actionbar", hud.craftwire$getOverlayMessageTime() > 0 ? text(hud.craftwire$getOverlayMessage()) : JsonNull.INSTANCE);
        boolean titleShown = hud.craftwire$getTitleTime() > 0;
        o.add("title", titleShown ? text(hud.craftwire$getTitle()) : JsonNull.INSTANCE);
        o.add("subtitle", titleShown ? text(hud.craftwire$getSubtitle()) : JsonNull.INSTANCE);
        o.add("sidebar", sidebar(mc.level.getScoreboard()));

        JsonArray tab = new JsonArray();
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
                Component display = info.getTabListDisplayName();
                tab.add(display != null ? display.getString() : info.getProfile().name());
            }
        }
        o.add("tabList", tab);
        o.addProperty("hidden", hud.craftwire$isHidden());
        return o;
    }

    private static JsonElement text(Component c) {
        return c == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(c.getString());
    }

    private static JsonElement sidebar(Scoreboard board) {
        Objective obj = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return JsonNull.INSTANCE;
        JsonObject s = new JsonObject();
        s.addProperty("title", obj.getDisplayName().getString());
        JsonArray entries = new JsonArray();
        board.listPlayerScores(obj).stream()
                .sorted(Comparator.comparingInt(PlayerScoreEntry::value).reversed())
                .limit(15)
                .forEach(e -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("name", e.display() != null ? e.display().getString() : e.owner());
                    row.addProperty("value", e.value());
                    entries.add(row);
                });
        s.add("entries", entries);
        return s;
    }
}
