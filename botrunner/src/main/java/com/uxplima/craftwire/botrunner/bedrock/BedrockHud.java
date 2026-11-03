package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.uxplima.craftwire.bedrock.packet.PlayerListPacket;
import com.uxplima.craftwire.bedrock.packet.UiPackets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a Bedrock client shows on its HUD, rebuilt from the packets: titles, action bar, boss bars, scoreboard
 * (sidebar, below name, list) and the player list. The JSON has the shape Java bots report (HudCopy), so hud_read
 * reads the same fields on every kind; Bedrock has no tab header or footer, which stay null. Runner loop thread.
 */
final class BedrockHud {
    private static final Pattern FORMAT_CODES = Pattern.compile("§.?");
    private static final long TICK_MS = 50;
    private static final int ACTION_BAR_TICKS = 60;
    private static final int SIDEBAR_LINES = 15;
    private static final String[] COLOURS = {"pink", "blue", "red", "green", "yellow", "purple", "white"};

    private record Bar(String name, float progress, int colour) {}

    private record Objective(String name, String display, int sortOrder) {}

    private record Score(String objective, String name, int value) {}

    private final Map<Long, Bar> bars = new LinkedHashMap<>();
    private final Map<String, Objective> slots = new HashMap<>();
    private final Map<Long, Score> scores = new HashMap<>();
    private final Map<UUID, PlayerListPacket.Entry> players = new LinkedHashMap<>();
    private final Map<Long, String> playerNames = new HashMap<>();
    private String title;
    private String subtitle;
    private long titleUntil;
    private int[] titleTimes = {10, 70, 20};
    private String actionBar;
    private long actionBarUntil;

    void title(UiPackets.Title t, long now) {
        switch (t.action()) {
            case 0 -> titleUntil = 0;
            case 1 -> {
                titleUntil = 0;
                title = null;
                subtitle = null;
                titleTimes = new int[] {10, 70, 20};
            }
            case 2 -> {
                title = t.text();
                titleUntil = now + (titleTimes[0] + titleTimes[1] + titleTimes[2]) * TICK_MS;
            }
            case 3 -> subtitle = t.text();
            case 4 -> {
                actionBar = t.text();
                actionBarUntil = now + ACTION_BAR_TICKS * TICK_MS;
            }
            case 5 -> titleTimes = new int[] {t.fadeIn(), t.stay(), t.fadeOut()};
            default -> { }
        }
    }

    /** Text type 5 (tip) and 3 (popup) show above the hotbar, where Java has its action bar. */
    void tip(String text, long now) {
        actionBar = text;
        actionBarUntil = now + ACTION_BAR_TICKS * TICK_MS;
    }

    void boss(UiPackets.BossEvent e) {
        Bar old = bars.get(e.bossId());
        switch (e.event()) {
            case 0 -> bars.put(e.bossId(), new Bar(e.title(), e.health(), e.colour()));
            case 2 -> bars.remove(e.bossId());
            case 4 -> {
                if (old != null) bars.put(e.bossId(), new Bar(old.name(), e.health(), old.colour()));
            }
            case 5 -> {
                if (old != null) bars.put(e.bossId(), new Bar(e.title(), old.progress(), old.colour()));
            }
            default -> { }
        }
    }

    void display(UiPackets.DisplayObjective d) {
        if (d.objective().isEmpty()) slots.remove(d.slot());
        else slots.put(d.slot(), new Objective(d.objective(), d.displayName(), d.sortOrder()));
    }

    void removeObjective(String objective) {
        slots.values().removeIf(o -> o.name().equals(objective));
        scores.values().removeIf(s -> s.objective().equals(objective));
    }

    void scores(UiPackets.SetScore s) {
        for (UiPackets.ScoreEntry e : s.entries()) {
            if (e.kind() == 0) {
                scores.remove(e.entryId());
                continue;
            }
            String name = e.kind() == 3 ? e.name() : playerNames.getOrDefault(parse(e.name()), e.name());
            scores.put(e.entryId(), new Score(e.objective(), name, e.score()));
        }
    }

    void players(PlayerListPacket p) {
        for (PlayerListPacket.Entry e : p.entries()) {
            if (p.add()) {
                players.put(e.uuid(), e);
                playerNames.put(e.uniqueId(), e.name());
            } else {
                PlayerListPacket.Entry old = players.remove(e.uuid());
                if (old != null) playerNames.remove(old.uniqueId());
            }
        }
    }

    /** The name of a listed player by entity unique id, or null. */
    String playerName(long uniqueId) {
        return playerNames.get(uniqueId);
    }

    JsonObject json(long now) {
        JsonObject o = new JsonObject();
        JsonArray bossbars = new JsonArray();
        for (Bar b : bars.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", plain(b.name()));
            j.addProperty("progress", b.progress());
            j.addProperty("color", b.colour() >= 0 && b.colour() < COLOURS.length ? COLOURS[b.colour()] : "purple");
            bossbars.add(j);
        }
        o.add("bossbars", bossbars);
        o.add("actionbar", actionBarUntil > now ? text(actionBar) : JsonNull.INSTANCE);
        boolean shown = titleUntil > now;
        o.add("title", shown ? text(title) : JsonNull.INSTANCE);
        o.add("subtitle", shown ? text(subtitle) : JsonNull.INSTANCE);
        o.add("sidebar", objective("sidebar", SIDEBAR_LINES));
        o.add("belowName", objective("belowname", Integer.MAX_VALUE));
        o.add("tab", tab());
        JsonArray tabList = new JsonArray();
        for (PlayerListPacket.Entry e : players.values()) tabList.add(e.name());
        o.add("tabList", tabList);
        return o;
    }

    /** A display slot's objective with its scores; sort order 0 ascending, 1 descending, as Bedrock draws them. */
    private JsonElement objective(String slot, int limit) {
        Objective obj = slots.get(slot);
        if (obj == null) return JsonNull.INSTANCE;
        List<Score> rows = new ArrayList<>();
        for (Score s : scores.values()) if (s.objective().equals(obj.name())) rows.add(s);
        Comparator<Score> order = Comparator.comparingInt(Score::value);
        rows.sort(obj.sortOrder() == 1 ? order.reversed() : order);
        JsonObject out = new JsonObject();
        out.addProperty("title", plain(obj.display()));
        JsonArray entries = new JsonArray();
        rows.stream().limit(limit).forEach(s -> {
            JsonObject row = new JsonObject();
            row.addProperty("name", plain(s.name()));
            row.addProperty("value", s.value());
            row.addProperty("shown", Integer.toString(s.value()));
            entries.add(row);
        });
        out.add("entries", entries);
        return out;
    }

    private JsonObject tab() {
        JsonObject t = new JsonObject();
        t.add("header", JsonNull.INSTANCE);
        t.add("footer", JsonNull.INSTANCE);
        Objective list = slots.get("list");
        JsonArray rows = new JsonArray();
        for (PlayerListPacket.Entry e : players.values()) {
            JsonObject p = new JsonObject();
            p.addProperty("name", plain(e.name()));
            p.addProperty("display", plain(e.name()));
            p.addProperty("ping", 0);
            if (list != null) {
                for (Score s : scores.values()) {
                    if (s.objective().equals(list.name()) && s.name().equals(e.name())) p.addProperty("score", s.value());
                }
            }
            rows.add(p);
        }
        t.add("players", rows);
        return t;
    }

    private static long parse(String n) {
        try {
            return Long.parseLong(n);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    static String plain(String s) {
        return s == null ? "" : FORMAT_CODES.matcher(s).replaceAll("");
    }

    private static JsonElement text(String s) {
        if (s == null) return JsonNull.INSTANCE;
        String p = plain(s);
        return p.isBlank() ? JsonNull.INSTANCE : new JsonPrimitive(p);
    }
}
