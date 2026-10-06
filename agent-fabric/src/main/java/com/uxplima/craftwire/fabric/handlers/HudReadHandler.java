package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.mixin.BossHealthOverlayAccessor;
import com.uxplima.craftwire.fabric.mixin.HudAccessor;
import com.uxplima.craftwire.fabric.mixin.PlayerTabOverlayAccessor;
import com.uxplima.craftwire.mc.HudJson;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
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
            b.addProperty("name", HudJson.plain(e.getName()));
            b.addProperty("progress", e.getProgress());
            b.addProperty("color", e.getColor().name().toLowerCase());
            bars.add(b);
        }
        o.add("bossbars", bars);
        o.add("actionbar", hud.craftwire$getOverlayMessageTime() > 0 ? HudJson.text(hud.craftwire$getOverlayMessage()) : JsonNull.INSTANCE);
        boolean titleShown = hud.craftwire$getTitleTime() > 0;
        o.add("title", titleShown ? HudJson.text(hud.craftwire$getTitle()) : JsonNull.INSTANCE);
        o.add("subtitle", titleShown ? HudJson.text(hud.craftwire$getSubtitle()) : JsonNull.INSTANCE);
        Scoreboard board = mc.level.getScoreboard();
        o.add("sidebar", HudJson.sidebar(board, mc.player.getScoreboardName()));
        o.add("belowName", HudJson.belowName(board));

        List<HudJson.TabEntry> listed = new ArrayList<>();
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
                listed.add(new HudJson.TabEntry(info.getProfile().name(), info.getTabListDisplayName(), info.getLatency(),
                        info.getGameMode(), info.getTabListOrder()));
            }
        }
        List<HudJson.TabEntry> ordered = HudJson.tabOrder(board, listed);
        PlayerTabOverlayAccessor overlay = (PlayerTabOverlayAccessor) mc.gui.hud.getTabList();
        o.add("tab", HudJson.tab(board, overlay.craftwire$getHeader(), overlay.craftwire$getFooter(), ordered));
        JsonArray tabList = new JsonArray();
        for (HudJson.TabEntry e : ordered) tabList.add(HudJson.tabName(board, e));
        o.add("tabList", tabList);
        o.addProperty("hidden", hud.craftwire$isHidden());
        return o;
    }
}
