package com.uxplima.craftwire.bot.path;

/** The world as the pathfinder sees it. */
@FunctionalInterface
public interface BlockView {
    Cell cell(int x, int y, int z);
}
