package com.uxplima.craftwire.bot.move;

import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.path.BlockView;

/**
 * A bot's body as the walker and the behaviors see it, implemented by each bot kind. Every method runs on the thread
 * that owns the bot (its entity scheduler in the plugin, its loop in the bot runner).
 */
public interface BotBody {
    Position position();

    float yaw();

    /** The world or dimension name; paths are cached per world. */
    String world();

    boolean onGround();

    boolean inWater();

    boolean onClimbable();

    /** Walked into a wall this tick. */
    boolean horizontalCollision();

    boolean sneaking();

    /** The blocks around the bot as the pathfinder reads them. */
    BlockView blocks();

    /** Applies this tick's movement keys. */
    void input(MoveInput in);

    /** Starts or stops sprinting the way a client tells the server (the sprint player command). */
    void sprinting(boolean on);

    /** Right-clicks a block like a player (a door or gate on the way); plugins see the interaction. */
    void useBlock(BlockPoint block);
}
