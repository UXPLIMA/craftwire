package com.uxplima.craftwire.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

/**
 * Scoreboard and tab-list JSON shared by the Fabric client and the Paper bots, so both read a HUD the same way.
 * Mirrors what the vanilla client draws: sidebar order and line text (team prefix + name + suffix), the number
 * format, the tab order and names. Compiled into both agents from this one source directory.
 */
public final class HudJson {
    private HudJson() {}

    /** One tab-list row, independent of the client's PlayerInfo so the bots can build it from packets. */
    public record TabEntry(String name, Component displayName, int latency, GameType gameMode, int order) {}

    private static final Pattern LEGACY_CODES = Pattern.compile("§.?");
    private static final int SIDEBAR_LINES = 15;
    private static final int TAB_PLAYERS = 80;

    private static final Comparator<PlayerScoreEntry> SIDEBAR_ORDER = Comparator.comparingInt(PlayerScoreEntry::value).reversed()
            .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

    /** Plain text with legacy § colour codes removed; plugins still put them inside literal strings. */
    public static String plain(Component c) {
        return LEGACY_CODES.matcher(c.getString()).replaceAll("");
    }

    /** Plain text, or null for a missing or blank component. */
    public static JsonElement text(Component c) {
        if (c == null) return JsonNull.INSTANCE;
        String s = plain(c);
        return s.isBlank() ? JsonNull.INSTANCE : new JsonPrimitive(s);
    }

    /** The sidebar `viewer` sees: their team colour's sidebar slot first, then the normal one. */
    public static JsonElement sidebar(Scoreboard board, String viewer) {
        Objective obj = null;
        PlayerTeam team = board.getPlayersTeam(viewer);
        if (team != null && team.getColor().isPresent()) obj = board.getDisplayObjective(team.getColor().get().displaySlot());
        if (obj == null) obj = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return JsonNull.INSTANCE;
        var format = obj.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        JsonObject s = new JsonObject();
        s.addProperty("title", plain(obj.getDisplayName()));
        JsonArray entries = new JsonArray();
        board.listPlayerScores(obj).stream()
                .filter(e -> !e.isHidden())
                .sorted(SIDEBAR_ORDER)
                .limit(SIDEBAR_LINES)
                .forEach(e -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("name", plain(PlayerTeam.formatNameForTeam(board.getPlayersTeam(e.owner()), e.ownerName())));
                    row.addProperty("value", e.value());
                    row.add("shown", text(e.formatValue(format)));
                    entries.add(row);
                });
        s.add("entries", entries);
        return s;
    }

    /** The below-name objective: its title and every visible score in it. */
    public static JsonElement belowName(Scoreboard board) {
        Objective obj = board.getDisplayObjective(DisplaySlot.BELOW_NAME);
        if (obj == null) return JsonNull.INSTANCE;
        var format = obj.numberFormatOrDefault(StyledFormat.NO_STYLE);
        JsonObject o = new JsonObject();
        o.addProperty("title", plain(obj.getDisplayName()));
        JsonArray entries = new JsonArray();
        board.listPlayerScores(obj).stream()
                .filter(e -> !e.isHidden())
                .sorted(Comparator.comparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER))
                .forEach(e -> {
                    JsonObject row = new JsonObject();
                    row.addProperty("name", e.owner());
                    row.addProperty("value", e.value());
                    row.add("shown", text(e.formatValue(format)));
                    entries.add(row);
                });
        o.add("entries", entries);
        return o;
    }

    /** Listed players in the order the tab list draws them, at most 80. */
    public static List<TabEntry> tabOrder(Scoreboard board, Collection<TabEntry> listed) {
        return listed.stream()
                .sorted(Comparator.comparingInt((TabEntry e) -> -e.order())
                        .thenComparingInt(e -> e.gameMode() == GameType.SPECTATOR ? 1 : 0)
                        .thenComparing(e -> teamName(board, e.name()))
                        .thenComparing(TabEntry::name, String::compareToIgnoreCase))
                .limit(TAB_PLAYERS)
                .toList();
    }

    /** The name the tab list shows: the server's display name, else the name decorated by the player's team. */
    public static String tabName(Scoreboard board, TabEntry e) {
        Component shown = e.displayName() != null ? e.displayName()
                : PlayerTeam.formatNameForTeam(board.getPlayersTeam(e.name()), Component.literal(e.name()));
        return plain(shown);
    }

    /** {header, footer, players:[{name, display, ping, gameMode, score?}]}; `ordered` comes from tabOrder. */
    public static JsonObject tab(Scoreboard board, Component header, Component footer, List<TabEntry> ordered) {
        JsonObject t = new JsonObject();
        t.add("header", text(header));
        t.add("footer", text(footer));
        Objective list = board.getDisplayObjective(DisplaySlot.LIST);
        JsonArray players = new JsonArray();
        for (TabEntry e : ordered) {
            JsonObject p = new JsonObject();
            p.addProperty("name", e.name());
            p.addProperty("display", tabName(board, e));
            p.addProperty("ping", e.latency());
            if (e.gameMode() != null) p.addProperty("gameMode", e.gameMode().getName().toLowerCase(Locale.ROOT));
            if (list != null) {
                ReadOnlyScoreInfo score = board.getPlayerScoreInfo(ScoreHolder.forNameOnly(e.name()), list);
                if (score != null) {
                    p.addProperty("score", score.value());
                    p.add("scoreShown", text(score.formatValue(list.numberFormatOrDefault(StyledFormat.PLAYER_LIST_DEFAULT))));
                }
            }
            players.add(p);
        }
        t.add("players", players);
        return t;
    }

    private static String teamName(Scoreboard board, String player) {
        PlayerTeam team = board.getPlayersTeam(player);
        return team == null ? "" : team.getName();
    }
}
