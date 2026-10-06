package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.mc.HudJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * What a client would show on its HUD, rebuilt from the packets sent to the bot the way the vanilla client
 * applies them: scoreboard and teams, tab list, boss bars, title, action bar. Built from packets rather than
 * the Bukkit API so plugins that send scoreboard packets directly (TAB and the like) are seen too.
 * Thread-safe: plugins may send packets off the server thread.
 */
final class BotHud {
    private static final long TICK_MS = 50;
    private static final int ACTION_BAR_TICKS = 60;
    private static final int[] DEFAULT_TITLE_TIMES = {10, 70, 20};

    private static final class Row {
        String name;
        Component displayName;
        int latency;
        GameType gameMode;
        int order;
        boolean listed;
    }

    private record Bar(Component name, float progress, BossEvent.BossBarColor color) {}

    private final Scoreboard board = new Scoreboard();
    private final Map<UUID, Row> players = new LinkedHashMap<>();
    private final Map<UUID, Bar> bars = new LinkedHashMap<>();
    private Component header;
    private Component footer;
    private Component title;
    private Component subtitle;
    private long titleUntil;
    private int[] titleTimes = DEFAULT_TITLE_TIMES.clone();
    private Component actionBar;
    private long actionBarUntil;

    synchronized void accept(Object packet, long now) {
        switch (packet) {
            case ClientboundSetObjectivePacket p -> objective(p);
            case ClientboundSetDisplayObjectivePacket p -> board.setDisplayObjective(p.getSlot(), board.getObjective(p.getObjectiveName()));
            case ClientboundSetScorePacket p -> score(p);
            case ClientboundResetScorePacket p -> resetScore(p);
            case ClientboundSetPlayerTeamPacket p -> team(p);
            case ClientboundPlayerInfoUpdatePacket p -> playerInfo(p);
            case ClientboundPlayerInfoRemovePacket p -> p.profileIds().forEach(players::remove);
            case ClientboundTabListPacket p -> {
                header = p.header().getString().isEmpty() ? null : p.header();
                footer = p.footer().getString().isEmpty() ? null : p.footer();
            }
            case ClientboundBossEventPacket p -> p.dispatch(new BarHandler());
            case ClientboundSetTitleTextPacket p -> {
                title = p.text();
                titleUntil = now + (long) (titleTimes[0] + titleTimes[1] + titleTimes[2]) * TICK_MS;
            }
            case ClientboundSetSubtitleTextPacket p -> subtitle = p.text();
            case ClientboundSetTitlesAnimationPacket p -> {
                titleTimes = new int[] {p.getFadeIn(), p.getStay(), p.getFadeOut()};
                if (title != null && titleUntil > now) titleUntil = now + (long) (titleTimes[0] + titleTimes[1] + titleTimes[2]) * TICK_MS;
            }
            case ClientboundClearTitlesPacket p -> {
                title = null;
                subtitle = null;
                titleUntil = 0;
                if (p.shouldResetTimes()) titleTimes = DEFAULT_TITLE_TIMES.clone();
            }
            case ClientboundSetActionBarTextPacket p -> actionBar(p.text(), now);
            case ClientboundSystemChatPacket p when p.overlay() -> actionBar(p.content(), now);
            default -> {}
        }
    }

    /** Same shape as the client's hud.read, minus `hidden`. `viewer` is the bot's name (team-coloured sidebars). */
    synchronized JsonObject json(String viewer, long now) {
        JsonObject o = new JsonObject();
        JsonArray bossbars = new JsonArray();
        for (Bar b : bars.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", HudJson.plain(b.name()));
            j.addProperty("progress", b.progress());
            j.addProperty("color", b.color().name().toLowerCase(Locale.ROOT));
            bossbars.add(j);
        }
        o.add("bossbars", bossbars);
        o.add("actionbar", actionBarUntil > now ? HudJson.text(actionBar) : JsonNull.INSTANCE);
        boolean titleShown = titleUntil > now;
        o.add("title", titleShown ? HudJson.text(title) : JsonNull.INSTANCE);
        o.add("subtitle", titleShown ? HudJson.text(subtitle) : JsonNull.INSTANCE);
        o.add("sidebar", HudJson.sidebar(board, viewer));
        o.add("belowName", HudJson.belowName(board));
        List<HudJson.TabEntry> listed = new ArrayList<>();
        for (Row r : players.values()) {
            if (r.listed) listed.add(new HudJson.TabEntry(r.name, r.displayName, r.latency, r.gameMode, r.order));
        }
        List<HudJson.TabEntry> ordered = HudJson.tabOrder(board, listed);
        o.add("tab", HudJson.tab(board, header, footer, ordered));
        JsonArray tabList = new JsonArray();
        for (HudJson.TabEntry e : ordered) tabList.add(HudJson.tabName(board, e));
        o.add("tabList", tabList);
        return o;
    }

    private void actionBar(Component text, long now) {
        actionBar = text;
        actionBarUntil = now + ACTION_BAR_TICKS * TICK_MS;
    }

    private void objective(ClientboundSetObjectivePacket p) {
        Objective existing = board.getObjective(p.getObjectiveName());
        if (p.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE) {
            if (existing != null) board.removeObjective(existing);
        } else if (existing == null) {
            board.addObjective(p.getObjectiveName(), ObjectiveCriteria.DUMMY, p.getDisplayName(), p.getRenderType(), false,
                    p.getNumberFormat().orElse(null));
        } else {
            existing.setRenderType(p.getRenderType());
            existing.setDisplayName(p.getDisplayName());
            existing.setNumberFormat(p.getNumberFormat().orElse(null));
        }
    }

    private void score(ClientboundSetScorePacket p) {
        Objective obj = board.getObjective(p.objectiveName());
        if (obj == null) return;
        ScoreAccess score = board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(p.owner()), obj, true);
        score.set(p.score());
        score.display(p.display().orElse(null));
        score.numberFormatOverride(p.numberFormat().orElse(null));
    }

    private void resetScore(ClientboundResetScorePacket p) {
        ScoreHolder holder = ScoreHolder.forNameOnly(p.owner());
        if (p.objectiveName() == null) {
            board.resetAllPlayerScores(holder);
            return;
        }
        Objective obj = board.getObjective(p.objectiveName());
        if (obj != null) board.resetSinglePlayerScore(holder, obj);
    }

    private void team(ClientboundSetPlayerTeamPacket p) {
        PlayerTeam team = board.getPlayerTeam(p.getName());
        if (p.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD && team == null) team = board.addPlayerTeam(p.getName());
        if (team == null) return;
        PlayerTeam t = team;
        p.getParameters().ifPresent(params -> {
            t.setDisplayName(params.displayName());
            t.setColor(params.color());
            t.unpackOptions(params.options());
            t.setNameTagVisibility(params.nameTagVisibility());
            t.setCollisionRule(params.collisionRule());
            t.setPlayerPrefix(params.playerPrefix());
            t.setPlayerSuffix(params.playerSuffix());
        });
        if (p.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD) {
            for (String player : p.getPlayers()) board.addPlayerToTeam(player, team);
        } else if (p.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            for (String player : p.getPlayers()) {
                if (board.getPlayersTeam(player) == team) board.removePlayerFromTeam(player, team);
            }
        }
        if (p.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE) board.removePlayerTeam(team);
    }

    private void playerInfo(ClientboundPlayerInfoUpdatePacket p) {
        for (ClientboundPlayerInfoUpdatePacket.Entry e : p.newEntries()) {
            players.computeIfAbsent(e.profileId(), id -> {
                Row r = new Row();
                r.name = e.profile().name();
                return r;
            });
        }
        for (ClientboundPlayerInfoUpdatePacket.Entry e : p.entries()) {
            Row r = players.get(e.profileId());
            if (r == null) continue;
            for (ClientboundPlayerInfoUpdatePacket.Action a : p.actions()) {
                switch (a) {
                    case UPDATE_GAME_MODE -> r.gameMode = e.gameMode();
                    case UPDATE_LISTED -> r.listed = e.listed();
                    case UPDATE_LATENCY -> r.latency = e.latency();
                    case UPDATE_DISPLAY_NAME -> r.displayName = e.displayName();
                    case UPDATE_LIST_ORDER -> r.order = e.listOrder();
                    default -> {}
                }
            }
        }
    }

    private final class BarHandler implements ClientboundBossEventPacket.Handler {
        @Override
        public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay,
                boolean darkenScreen, boolean playMusic, boolean createWorldFog) {
            bars.put(id, new Bar(name, progress, color));
        }

        @Override
        public void remove(UUID id) {
            bars.remove(id);
        }

        @Override
        public void updateProgress(UUID id, float progress) {
            bars.computeIfPresent(id, (k, b) -> new Bar(b.name(), progress, b.color()));
        }

        @Override
        public void updateName(UUID id, Component name) {
            bars.computeIfPresent(id, (k, b) -> new Bar(name, b.progress(), b.color()));
        }

        @Override
        public void updateStyle(UUID id, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay) {
            bars.computeIfPresent(id, (k, b) -> new Bar(b.name(), b.progress(), color));
        }
    }
}
