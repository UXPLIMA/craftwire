package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.Click;
import com.uxplima.craftwire.bot.gui.ClickPlan;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Opens a menu (with `open`, a command), clicks through `clicks` with the full click vocabulary, closes it and
 * repeats on a cadence: a plugin GUI under constant use, for load tests and dupe checks.
 */
final class MenuClick implements Behavior {
    private enum Phase { IDLE, WAIT_OPEN, CLICK, SETTLE }

    private static final long OPEN_TIMEOUT_MS = 3000;

    private final String open;
    private final List<Click> clicks;
    private final boolean close;
    private final long settleMs;
    private final Cadence cadence;
    private Phase phase = Phase.IDLE;
    private long phaseUntil;
    private int index;
    private int cycles;
    private int failures;
    private String lastTitle;

    private MenuClick(String open, List<Click> clicks, boolean close, long settleMs, Cadence cadence) {
        this.open = open;
        this.clicks = clicks;
        this.close = close;
        this.settleMs = settleMs;
        this.cadence = cadence;
    }

    /** open (a command, optional), clicks [{slot, click, …}] (1-32), close (true), settleMs (150), intervalMs (4000). */
    static MenuClick parse(JsonObject p, SplittableRandom random) {
        List<Click> clicks = new ArrayList<>();
        for (JsonElement e : Params.array(p, "clicks")) {
            if (!e.isJsonObject()) throw BotErrors.invalid("clicks are {slot, click} objects, as for gui_click");
            clicks.add(Click.parse(e.getAsJsonObject()));
        }
        if (clicks.isEmpty() || clicks.size() > 32) throw BotErrors.invalid("menu_click takes 1-32 clicks");
        String open = Params.optString(p, "open").map(s -> s.startsWith("/") ? s.substring(1) : s).orElse(null);
        return new MenuClick(open, List.copyOf(clicks), Params.bool(p, "close", true), Params.clampedLong(p, "settleMs", 150L, 0L, 5000L),
                Cadence.parse(p, 4000, random));
    }

    @Override
    public String type() {
        return "menu_click";
    }

    @Override
    public void tick(BotControl bot, long now) {
        if (!bot.alive()) return;
        switch (phase) {
            case IDLE -> {
                if (!cadence.due(now)) return;
                if (open != null) bot.command(open);
                phase = Phase.WAIT_OPEN;
                phaseUntil = now + OPEN_TIMEOUT_MS;
            }
            case WAIT_OPEN -> {
                GuiView v = bot.gui();
                if (v.open()) {
                    lastTitle = v.title();
                    index = 0;
                    phase = Phase.CLICK;
                } else if (now > phaseUntil) {
                    failures++;
                    phase = Phase.IDLE;
                }
            }
            case CLICK -> clickNext(bot, now);
            case SETTLE -> {
                if (now >= phaseUntil) phase = index < clicks.size() ? Phase.CLICK : Phase.IDLE;
            }
        }
    }

    private void clickNext(BotControl bot, long now) {
        GuiView v = bot.gui();
        if (!v.open()) {
            // The plugin closed the menu (or a click opened another and then closed): the cycle ends here.
            cycles++;
            phase = Phase.IDLE;
            return;
        }
        Click c = clicks.get(index++);
        c.check(v.slotCount());
        for (RawClick raw : ClickPlan.of(c)) bot.click(raw);
        if (index >= clicks.size()) {
            cycles++;
            if (close && bot.gui().open()) bot.closeScreen();
        }
        phase = Phase.SETTLE;
        phaseUntil = now + settleMs;
    }

    @Override
    public JsonObject describe() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type());
        if (open != null) o.addProperty("open", open);
        o.addProperty("clicks", clicks.size());
        o.addProperty("cycles", cycles);
        o.addProperty("failures", failures);
        if (lastTitle != null) o.addProperty("lastTitle", lastTitle);
        return o;
    }
}
