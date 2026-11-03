package com.uxplima.craftwire.botrunner.javabot.bot;

import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.MoveInput;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

/**
 * What the bot tells the server about its movement each tick, the way LocalPlayer does: the input keys when they
 * change, a position and rotation packet when either changed (or every 20 ticks), a status-only packet when only
 * the ground flag changed, and the end-of-tick marker. Runner loop thread.
 */
final class JavaMoves {
    /** LocalPlayer.sendPosition sends a position at least once a second even when standing still. */
    private static final int POSITION_EVERY = 20;
    private static final double MOVE_EPS_SQ = 2.0E-4 * 2.0E-4;

    private final JavaBot bot;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private float lastPitch;
    private boolean lastGround;
    private boolean lastHorizontal;
    private int sinceSent;
    private Input lastInput = Input.EMPTY;

    JavaMoves(JavaBot bot) {
        this.bot = bot;
    }

    /** After a teleport the confirmation carries the position, so the next tick only reports what changes after it. */
    void sent(double x, double y, double z, float yaw, float pitch) {
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        lastPitch = pitch;
        sinceSent = 0;
    }

    void tick(boolean moved) {
        JavaPhysics b = bot.body;
        sendInput(b);
        Position p = b.position();
        double dx = p.x() - lastX;
        double dy = p.y() - lastY;
        double dz = p.z() - lastZ;
        boolean position = dx * dx + dy * dy + dz * dz > MOVE_EPS_SQ || ++sinceSent >= POSITION_EVERY;
        boolean rotation = b.yaw() != lastYaw || b.pitch() != lastPitch;
        boolean ground = b.onGround();
        boolean horizontal = b.horizontalCollision();
        if (position && rotation) {
            bot.send(new ServerboundMovePlayerPacket.PosRot(p.x(), p.y(), p.z(), b.yaw(), b.pitch(), ground, horizontal));
        } else if (position) {
            bot.send(new ServerboundMovePlayerPacket.Pos(p.x(), p.y(), p.z(), ground, horizontal));
        } else if (rotation) {
            bot.send(new ServerboundMovePlayerPacket.Rot(b.yaw(), b.pitch(), ground, horizontal));
        } else if (ground != lastGround || horizontal != lastHorizontal) {
            bot.send(new ServerboundMovePlayerPacket.StatusOnly(ground, horizontal));
        }
        if (position) {
            lastX = p.x();
            lastY = p.y();
            lastZ = p.z();
            sinceSent = 0;
        }
        lastYaw = b.yaw();
        lastPitch = b.pitch();
        lastGround = ground;
        lastHorizontal = horizontal;
        bot.send(ServerboundClientTickEndPacket.INSTANCE);
    }

    /** ServerboundPlayerInputPacket: the server reads sneaking from the shift key and fires PlayerInputEvent on change. */
    private void sendInput(JavaPhysics b) {
        MoveInput in = b.lastInput();
        Input now = new Input(in.forward() > 0, false, false, false, in.jump(), b.sneaking(), in.sprint());
        if (now.equals(lastInput)) return;
        lastInput = now;
        bot.send(new ServerboundPlayerInputPacket(now));
    }

    /** Sneak on or off through the input keys, as a player holding shift. */
    void sneak(boolean on) {
        bot.body.sneak(on);
        sendInput(bot.body);
    }

    /**
     * The sprint player command. Its public constructor takes a client Entity, which the runner has none of, so the
     * packet is decoded from its wire form (entity id, action, data) through the vanilla codec.
     */
    void sprint(boolean on) {
        bot.body.sprintState(on);
        command(on ? ServerboundPlayerCommandPacket.Action.START_SPRINTING : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
    }

    void command(ServerboundPlayerCommandPacket.Action action) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(bot.life.entityId());
            buf.writeEnum(action);
            buf.writeVarInt(0);
            bot.send(ServerboundPlayerCommandPacket.STREAM_CODEC.decode(buf));
        } finally {
            buf.release();
        }
    }
}
