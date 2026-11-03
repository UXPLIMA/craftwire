package com.uxplima.craftwire.botrunner.world;

import com.uxplima.craftwire.bot.path.Cell;

/**
 * What a block state means to a walking player, per protocol version: the Java driver builds it from the vanilla
 * block registry of the server jar, the Bedrock driver from the block palette the server sent.
 */
public interface BlockClassifier {
    Cell classify(int state);

    /** The namespaced block id of a state (minecraft:stone), for world queries and the agent's answers. */
    String blockId(int state);
}
