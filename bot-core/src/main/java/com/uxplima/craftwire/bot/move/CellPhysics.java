package com.uxplima.craftwire.bot.move;

import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.path.BlockView;
import com.uxplima.craftwire.bot.path.Cell;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A protocol bot's own client physics, as the vanilla Java client runs LocalPlayer.travel each tick: ground and air
 * acceleration, friction, gravity and drag, jumping (with the sprint-jump boost), swimming, ladders, and axis-by-axis
 * collision with step-up against the blocks the bot knows. Blocks collide as full-width boxes of their cell height,
 * which is at least as tall as the real shape, so the server never sees the bot inside a block. In a chunk the bot
 * has not received the body stays still, as a vanilla client does. Shared by the Java and Bedrock protocol bots, whose
 * players move the same way (Bedrock's movement constants are the Java ones). The bot's own thread.
 */
public final class CellPhysics implements BotBody {
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;
    private static final double STEP = 0.6;
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.98;
    private static final double JUMP = 0.42;
    /** Block friction 0.6 times the 0.91 every entity keeps of its speed. */
    private static final double GROUND_FRICTION = 0.6 * 0.91;
    private static final double WALK_SPEED = 0.1;
    private static final double EPS = 1e-7;

    private final Supplier<BlockView> blocks;
    private final Supplier<String> world;
    private final Consumer<Boolean> sprintSink;
    private final Consumer<BlockPoint> useSink;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private double vx;
    private double vy;
    private double vz;
    private boolean onGround;
    private boolean horizontal;
    private boolean sneaking;
    private boolean sprinting;
    private MoveInput input = MoveInput.stop(0f);
    private boolean jumpOnce;

    public CellPhysics(Supplier<BlockView> blocks, Supplier<String> world, Consumer<Boolean> sprintSink, Consumer<BlockPoint> useSink) {
        this.blocks = blocks;
        this.world = world;
        this.sprintSink = sprintSink;
        this.useSink = useSink;
    }

    /** A server teleport (or the spawn position): the body is put there and stops. */
    public void teleport(double nx, double ny, double nz, float nyaw, float npitch) {
        x = nx;
        y = ny;
        z = nz;
        yaw = nyaw;
        pitch = npitch;
        vx = 0;
        vy = 0;
        vz = 0;
    }

    public void look(float nyaw, float npitch) {
        yaw = nyaw;
        pitch = npitch;
    }

    /** ClientboundSetEntityMotionPacket for the bot itself (knockback, plugin launches). */
    public void motion(double mx, double my, double mz) {
        vx = mx;
        vy = my;
        vz = mz;
    }

    public void sneak(boolean on) {
        sneaking = on;
    }

    public void sprintState(boolean on) {
        sprinting = on;
    }

    /** One jump at the next tick when standing, for the jump action. */
    public boolean jumpNow() {
        if (!onGround && !inWater()) return false;
        jumpOnce = true;
        return true;
    }

    public float pitch() {
        return pitch;
    }

    /** One client tick. Returns whether the position changed. */
    public boolean step() {
        BlockView view = blocks.get();
        if (view.cell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).kind() == Cell.Kind.UNKNOWN) return false;
        double ox = x;
        double oy = y;
        double oz = z;
        boolean jump = input.jump() || jumpOnce;
        jumpOnce = false;
        if (inWater()) swim(jump);
        else if (onClimbable()) climb(jump);
        else walk(jump);
        move(view);
        return ox != x || oy != y || oz != z;
    }

    private void walk(boolean jump) {
        if (jump && onGround) {
            vy = JUMP;
            if (sprinting) {
                double r = Math.toRadians(yaw);
                vx -= Math.sin(r) * 0.2;
                vz += Math.cos(r) * 0.2;
            }
        }
        double speed = WALK_SPEED * (sprinting ? 1.3 : 1.0);
        double accel = onGround ? speed * (0.21600002 / (GROUND_FRICTION * GROUND_FRICTION * GROUND_FRICTION)) : (sprinting ? 0.026 : 0.02);
        accelerate(accel);
        vy = (vy - GRAVITY) * AIR_DRAG;
    }

    private void swim(boolean jump) {
        accelerate(0.02);
        if (jump) vy += 0.04;
        vy -= 0.02;
    }

    private void climb(boolean jump) {
        accelerate(onGround ? 0.1 : 0.02);
        vx = Math.clamp(vx, -0.15, 0.15);
        vz = Math.clamp(vz, -0.15, 0.15);
        vy = Math.max(vy, sneaking ? 0 : -0.15);
        if (jump || horizontal) vy = 0.2;
        vy = (vy - GRAVITY) * AIR_DRAG;
    }

    /** Entity.moveRelative: the input as a push along the facing yaw (forward only; bots never strafe). */
    private void accelerate(double accel) {
        double forward = input.forward() * 0.98 * (sneaking ? 0.3 : 1.0);
        if (forward == 0) return;
        double r = Math.toRadians(input.yaw());
        vx += -Math.sin(r) * forward * accel;
        vz += Math.cos(r) * forward * accel;
        yaw = input.yaw();
    }

    /** Moves by the velocity with collision, then applies the medium's drag. */
    private void move(BlockView view) {
        double dy = clipY(view, vy);
        double dx = clipX(view, vx, y + dy);
        double dz = clipZ(view, vz, y + dy, x + dx);
        boolean blockedX = dx != vx;
        boolean blockedZ = dz != vz;
        boolean landed = vy < 0 && dy != vy;
        boolean stepped = false;
        if ((blockedX || blockedZ) && (onGround || landed)) {
            double[] step = stepUp(view);
            if (step != null) {
                dx = step[0];
                dz = step[1];
                dy = step[2];
                blockedX = dx != vx;
                blockedZ = dz != vz;
                stepped = true;
            }
        }
        onGround = landed || stepped;
        y += dy;
        x += dx;
        z += dz;
        horizontal = blockedX || blockedZ;
        if (dy != vy) vy = 0;
        if (blockedX) vx = 0;
        if (blockedZ) vz = 0;
        double drag = inWater() ? 0.8 : onGround ? GROUND_FRICTION : 0.91;
        vx *= drag;
        vz *= drag;
        if (inWater()) vy *= 0.8;
    }

    /** Entity.collide's step: lift by up to 0.6, move, settle back down; null when that gets no further. */
    private double[] stepUp(BlockView view) {
        double up = clipYAt(view, STEP, x, y, z);
        double sx = clipXAt(view, vx, x, y + up, z);
        double sz = clipZAt(view, vz, x + sx, y + up, z);
        double down = clipYAt(view, -up, x + sx, y + up, z + sz);
        double flatX = clipX(view, vx, y);
        double flatZ = clipZ(view, vz, y, x + flatX);
        if (sx * sx + sz * sz <= flatX * flatX + flatZ * flatZ + EPS) return null;
        return new double[] {sx, sz, up + down};
    }

    private double clipY(BlockView view, double dy) {
        return clipYAt(view, dy, x, y, z);
    }

    private double clipX(BlockView view, double dx, double atY) {
        return clipXAt(view, dx, x, atY, z);
    }

    private double clipZ(BlockView view, double dz, double atY, double atX) {
        return clipZAt(view, dz, atX, atY, z);
    }

    private static double clipYAt(BlockView view, double dy, double px, double py, double pz) {
        if (dy == 0) return 0;
        int x0 = floor(px - HALF_WIDTH + EPS);
        int x1 = floor(px + HALF_WIDTH - EPS);
        int z0 = floor(pz - HALF_WIDTH + EPS);
        int z1 = floor(pz + HALF_WIDTH - EPS);
        int y0 = floor(Math.min(py, py + dy) - 1);
        int y1 = floor(Math.max(py + HEIGHT, py + HEIGHT + dy));
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                for (int by = y0; by <= y1; by++) {
                    double h = solidHeight(view.cell(bx, by, bz));
                    if (h <= 0) continue;
                    double top = by + h;
                    if (dy < 0 && top <= py + EPS) dy = Math.max(dy, top - py);
                    else if (dy > 0 && by >= py + HEIGHT - EPS) dy = Math.min(dy, by - (py + HEIGHT));
                }
            }
        }
        return dy;
    }

    private static double clipXAt(BlockView view, double dx, double px, double py, double pz) {
        if (dx == 0) return 0;
        int y0 = floor(py + EPS);
        int y1 = floor(py + HEIGHT - EPS);
        int z0 = floor(pz - HALF_WIDTH + EPS);
        int z1 = floor(pz + HALF_WIDTH - EPS);
        int x0 = floor(Math.min(px - HALF_WIDTH, px - HALF_WIDTH + dx));
        int x1 = floor(Math.max(px + HALF_WIDTH, px + HALF_WIDTH + dx));
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                for (int by = y0 - 1; by <= y1; by++) {
                    double h = solidHeight(view.cell(bx, by, bz));
                    if (h <= 0 || by + h <= py + EPS) continue;
                    if (dx > 0 && bx >= px + HALF_WIDTH - EPS) dx = Math.min(dx, bx - (px + HALF_WIDTH));
                    else if (dx < 0 && bx + 1 <= px - HALF_WIDTH + EPS) dx = Math.max(dx, bx + 1 - (px - HALF_WIDTH));
                }
            }
        }
        return dx;
    }

    private static double clipZAt(BlockView view, double dz, double px, double py, double pz) {
        if (dz == 0) return 0;
        int y0 = floor(py + EPS);
        int y1 = floor(py + HEIGHT - EPS);
        int x0 = floor(px - HALF_WIDTH + EPS);
        int x1 = floor(px + HALF_WIDTH - EPS);
        int z0 = floor(Math.min(pz - HALF_WIDTH, pz - HALF_WIDTH + dz));
        int z1 = floor(Math.max(pz + HALF_WIDTH, pz + HALF_WIDTH + dz));
        for (int bz = z0; bz <= z1; bz++) {
            for (int bx = x0; bx <= x1; bx++) {
                for (int by = y0 - 1; by <= y1; by++) {
                    double h = solidHeight(view.cell(bx, by, bz));
                    if (h <= 0 || by + h <= py + EPS) continue;
                    if (dz > 0 && bz >= pz + HALF_WIDTH - EPS) dz = Math.min(dz, bz - (pz + HALF_WIDTH));
                    else if (dz < 0 && bz + 1 <= pz - HALF_WIDTH + EPS) dz = Math.max(dz, bz + 1 - (pz - HALF_WIDTH));
                }
            }
        }
        return dz;
    }

    /** The height a cell collides up to; 0 for what a player passes through. */
    private static double solidHeight(Cell c) {
        return switch (c.kind()) {
            case AIR, WATER, CLIMB -> 0;
            case LOW -> c.height();
            default -> c.height() <= 0 ? 1 : c.height();
        };
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    @Override
    public Position position() {
        return new Position(x, y, z);
    }

    @Override
    public float yaw() {
        return yaw;
    }

    @Override
    public String world() {
        return world.get();
    }

    @Override
    public boolean onGround() {
        return onGround;
    }

    @Override
    public boolean inWater() {
        BlockView view = blocks.get();
        return view.cell(floor(x), floor(y + 0.1), floor(z)).kind() == Cell.Kind.WATER
                || view.cell(floor(x), floor(y + 1.0), floor(z)).kind() == Cell.Kind.WATER;
    }

    @Override
    public boolean onClimbable() {
        return blocks.get().cell(floor(x), floor(y + EPS), floor(z)).kind() == Cell.Kind.CLIMB;
    }

    @Override
    public boolean horizontalCollision() {
        return horizontal;
    }

    @Override
    public boolean sneaking() {
        return sneaking;
    }

    @Override
    public BlockView blocks() {
        return blocks.get();
    }

    @Override
    public void input(MoveInput in) {
        input = in;
    }

    @Override
    public void sprinting(boolean on) {
        if (on == sprinting) return;
        sprinting = on;
        sprintSink.accept(on);
    }

    @Override
    public void useBlock(BlockPoint block) {
        useSink.accept(block);
    }

    public boolean sprintingNow() {
        return sprinting;
    }

    public MoveInput lastInput() {
        return input;
    }
}
