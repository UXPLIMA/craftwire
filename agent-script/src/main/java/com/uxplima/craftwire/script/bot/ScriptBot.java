package com.uxplima.craftwire.script.bot;

import com.uxplima.craftwire.bot.behavior.BotControl;
import com.uxplima.craftwire.bot.gui.Click;
import com.uxplima.craftwire.bot.gui.ClickPlan;
import com.uxplima.craftwire.bot.gui.ClickType;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.WalkOptions;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.graalvm.polyglot.HostAccess;

/**
 * One bot as a script sees it. Reads come from a snapshot taken at the start of the bot's tick; every action is
 * queued and runs on the bot's own thread at its next tick. So a script may touch any of its bots from any
 * handler, also on Folia where each bot ticks on its own region thread.
 */
public final class ScriptBot {
    private final String name;
    private final Queue<Consumer<BotControl>> actions = new ConcurrentLinkedQueue<>();
    private volatile Position position = new Position(0, 0, 0);
    private volatile boolean alive;
    private volatile boolean moving;
    private volatile String menuTitle;

    ScriptBot(String name) {
        this.name = name;
    }

    /** On the bot's thread, before handlers run: refresh what scripts read, then run what they queued. */
    void sync(BotControl bot) {
        position = bot.position();
        alive = bot.alive();
        moving = bot.moving();
        GuiView v = bot.gui();
        menuTitle = v.open() ? v.title() : null;
        Consumer<BotControl> a;
        int budget = 64;   // one handler loop must not flood a single tick
        while (budget-- > 0 && (a = actions.poll()) != null) a.accept(bot);
    }

    @HostAccess.Export
    public String name() {
        return name;
    }

    @HostAccess.Export
    public double x() {
        return position.x();
    }

    @HostAccess.Export
    public double y() {
        return position.y();
    }

    @HostAccess.Export
    public double z() {
        return position.z();
    }

    @HostAccess.Export
    public boolean alive() {
        return alive;
    }

    @HostAccess.Export
    public boolean moving() {
        return moving;
    }

    /** The open menu's title, or null. */
    @HostAccess.Export
    public String menu() {
        return menuTitle;
    }

    @HostAccess.Export
    public void chat(String text) {
        actions.add(b -> b.chat(text));
    }

    @HostAccess.Export
    public void command(String command) {
        String c = command.startsWith("/") ? command.substring(1) : command;
        actions.add(b -> b.command(c));
    }

    @HostAccess.Export
    public void moveTo(double x, double y, double z) {
        actions.add(b -> b.moveTo(WalkOptions.to(new Position(x, y, z), false, 30_000)));
    }

    @HostAccess.Export
    public void stop() {
        actions.add(BotControl::stopMoving);
    }

    /** click(slot, type) with a vocabulary type such as "left", "shift", "drop"; number keys use click(slot, "number", key). */
    @HostAccess.Export
    public void click(int slot, String type) {
        click(slot, type, 1);
    }

    @HostAccess.Export
    public void click(int slot, String type, int key) {
        ClickType t = ClickType.parse(type);
        Click c = new Click(t, slot, t == ClickType.NUMBER ? key : 0, "left", List.of());
        actions.add(b -> {
            GuiView v = b.gui();
            c.check(v.slotCount());
            for (RawClick raw : ClickPlan.of(c)) b.click(raw);
        });
    }

    @HostAccess.Export
    public void close() {
        actions.add(BotControl::closeScreen);
    }

    @HostAccess.Export
    public void look(double yaw, double pitch) {
        actions.add(b -> b.look((float) yaw, (float) pitch));
    }

    @HostAccess.Export
    public void swing() {
        actions.add(BotControl::swing);
    }
}
