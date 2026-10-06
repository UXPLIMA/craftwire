package com.uxplima.craftwire.paper.bot.path;

/** The world as the pathfinder sees it. */
@FunctionalInterface
public interface BlockView {
    Cell cell(int x, int y, int z);
}
