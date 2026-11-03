package com.uxplima.craftwire.botrunner.world;

import java.util.UUID;

/**
 * An entity some bot of this world can see. Mutable: movement packets update it in place, so hundreds of bots
 * tracking the same entity share one object. Runner loop thread.
 */
public final class TrackedEntity {
    private final long id;
    private final UUID uuid;
    private final String type;
    private String name;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    public TrackedEntity(long id, UUID uuid, String type, double x, double y, double z) {
        this.id = id;
        this.uuid = uuid;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public long id() {
        return id;
    }

    public UUID uuid() {
        return uuid;
    }

    public String type() {
        return type;
    }

    /** The player name for players (from the player list), else a custom name or null. */
    public String name() {
        return name;
    }

    public void name(String n) {
        name = n;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public void moveTo(double nx, double ny, double nz) {
        x = nx;
        y = ny;
        z = nz;
    }

    public void moveBy(double dx, double dy, double dz) {
        x += dx;
        y += dy;
        z += dz;
    }

    public void rotate(float nyaw, float npitch) {
        yaw = nyaw;
        pitch = npitch;
    }
}
