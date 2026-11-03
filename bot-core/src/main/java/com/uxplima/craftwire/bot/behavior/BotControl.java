package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.GuiView;
import com.uxplima.craftwire.bot.gui.RawClick;
import com.uxplima.craftwire.bot.inbox.Inbox;
import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.WalkOptions;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * What behaviors and scripts may do with a bot, the same on every kind. Every method runs on the bot's own thread;
 * a behavior's tick is called there.
 */
public interface BotControl {
    String name();

    BotKind kind();

    Position position();

    boolean alive();

    boolean moving();

    /** Starts a walk; a walk already going ends with reason "replaced". */
    CompletableFuture<JsonObject> moveTo(WalkOptions o);

    void stopMoving();

    void chat(String text);

    /** A command without the slash, sent the way the client sends commands. */
    void command(String command);

    /** The open menu, or the bot's own inventory when none is open. */
    GuiView gui();

    void click(RawClick c);

    void closeScreen();

    void look(float yaw, float pitch);

    /** Swings the main hand (the animate packet). */
    void swing();

    /** Where a player with this name is, if the bot can see them (same world, within tracking range). */
    Optional<Position> playerPosition(String name);

    Inbox inbox();
}
