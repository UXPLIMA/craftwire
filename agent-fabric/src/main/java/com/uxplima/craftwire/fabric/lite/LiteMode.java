package com.uxplima.craftwire.fabric.lite;

import java.util.function.BooleanSupplier;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

/**
 * Lite headless mode (client_process lite:true starts the game with -Dcraftwire.lite=true): in a world no frame is
 * drawn unless a screenshot or recording needs one, the game loop idles at the tick rate, advancement and statistics
 * packets are dropped, and the view is the smallest the server accepts. The aim is many hidden clients on one machine
 * (the design's target is under 1 GB each). Read on the render thread.
 */
public final class LiteMode {
    public static final boolean ENABLED = Boolean.getBoolean("craftwire.lite");
    /** One loop per client tick: ticks stay on time and the CPU is not spent on frames nobody sees. */
    public static final int IDLE_FPS = 20;
    private static final int RENDER_DISTANCE = 2;
    private static final int SIMULATION_DISTANCE = 5;

    private static volatile BooleanSupplier capturing = () -> false;

    private LiteMode() {}

    /** `captureActive` tells when a screenshot or recording is waiting for frames. */
    public static void install(BooleanSupplier captureActive) {
        capturing = captureActive;
    }

    /**
     * Whether this frame is drawn. Loading and joining always draw: the loading overlay finishes resource reloads from
     * its render method, and nothing is in a world to skip then.
     */
    public static boolean drawFrame() {
        if (!ENABLED) return true;
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null || mc.gui.overlay() != null || capturing.getAsBoolean();
    }

    /** The smallest view; set at start and not saved, so options.txt keeps the user's values for normal runs. */
    public static void applyOptions(Minecraft mc) {
        if (!ENABLED) return;
        Options o = mc.options;
        o.renderDistance().set(RENDER_DISTANCE);
        o.simulationDistance().set(SIMULATION_DISTANCE);
        o.cloudStatus().set(CloudStatus.OFF);
        o.particles().set(ParticleStatus.MINIMAL);
        o.entityShadows().set(false);
    }
}
