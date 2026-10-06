package com.uxplima.craftwire.paper.world.render;

import java.awt.image.BufferedImage;

/**
 * A rendered image. The world part starts at ({@code originX}, {@code originY}); margins to its left and top hold
 * the grid labels. {@code unloadedColumns} counts columns whose chunk was not available.
 */
public record Render(BufferedImage image, int originX, int originY, int unloadedColumns) {}
