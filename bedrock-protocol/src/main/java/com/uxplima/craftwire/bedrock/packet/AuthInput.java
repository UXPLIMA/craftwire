package com.uxplima.craftwire.bedrock.packet;

import static com.uxplima.craftwire.bedrock.packet.LoginOut.start;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;
import java.util.BitSet;
import java.util.List;

/**
 * PlayerAuthInput, the packet a client sends every tick with its position, rotation and input flags. With
 * server-authoritative movement it is the only way the bot moves; block breaking and stack requests can ride on it.
 *
 * @param flags        input flag ids set this tick (the InputFlag order of the protocol, see the constants)
 * @param blockActions PlayerBlockAction entries (action, x, y, z, face), sent with FLAG_BLOCK_ACTIONS
 */
public record AuthInput(float pitch, float yaw, float x, float y, float z, float moveX, float moveZ, BitSet flags, long tick,
                        float dx, float dy, float dz, ItemUse itemUse, StackRequest stackRequest, List<int[]> blockActions) {
    public static final int FLAG_ASCEND = 0;
    public static final int FLAG_JUMP_DOWN = 3;
    public static final int FLAG_SPRINT_DOWN = 4;
    public static final int FLAG_JUMPING = 6;
    public static final int FLAG_SNEAKING = 8;
    public static final int FLAG_SNEAK_DOWN = 9;
    public static final int FLAG_UP = 10;
    public static final int FLAG_DOWN = 11;
    public static final int FLAG_LEFT = 12;
    public static final int FLAG_RIGHT = 13;
    public static final int FLAG_WANT_UP = 16;
    public static final int FLAG_SPRINTING = 20;
    public static final int FLAG_START_SPRINTING = 25;
    public static final int FLAG_STOP_SPRINTING = 26;
    public static final int FLAG_START_SNEAKING = 27;
    public static final int FLAG_STOP_SNEAKING = 28;
    public static final int FLAG_START_JUMPING = 31;
    public static final int FLAG_ITEM_INTERACTION = 34;
    public static final int FLAG_BLOCK_ACTIONS = 35;
    public static final int FLAG_ITEM_STACK_REQUEST = 36;
    public static final int FLAG_HANDLED_TELEPORT = 37;
    public static final int FLAG_HORIZONTAL_COLLISION = 49;
    public static final int FLAG_VERTICAL_COLLISION = 50;

    /** Input mode 2 touch, matching the client data the login sends (an Android phone, ClientData). */
    private static final int INPUT_MODE_TOUCH = 2;
    private static final int PLAY_MODE_NORMAL = 0;
    private static final int INTERACTION_CROSSHAIR = 1;

    public ByteBuf encode(int shieldId) {
        ByteBuf b = start(PacketIds.PLAYER_AUTH_INPUT);
        b.writeFloatLE(pitch);
        b.writeFloatLE(yaw);
        Bin.writeVec3(b, x, y, z);
        Bin.writeVec2(b, moveX, moveZ);
        b.writeFloatLE(yaw);
        Bin.writeVarUInt(b, flags.cardinality());
        for (int f = flags.nextSetBit(0); f >= 0; f = flags.nextSetBit(f + 1)) Bin.writeVarInt(b, f);
        Bin.writeVarUInt(b, INPUT_MODE_TOUCH);
        Bin.writeVarUInt(b, PLAY_MODE_NORMAL);
        Bin.writeVarInt(b, INTERACTION_CROSSHAIR);
        b.writeFloatLE(pitch);
        b.writeFloatLE(yaw);
        Bin.writeVarULong(b, tick);
        Bin.writeVec3(b, dx, dy, dz);
        Bin.writeBool(b, itemUse != null);
        if (itemUse != null) {
            Bin.writeVarInt(b, 0);
            Bin.writeBool(b, false);
            Bin.writeVarUInt(b, 0);
            itemUse.write(b, shieldId);
        }
        Bin.writeBool(b, stackRequest != null);
        if (stackRequest != null) stackRequest.write(b, shieldId);
        Bin.writeBool(b, blockActions != null);
        if (blockActions != null) writeBlockActions(b);
        Bin.writeBool(b, false);
        Bin.writeBool(b, false);
        Bin.writeVec2(b, moveX, moveZ);
        writeCamera(b);
        Bin.writeVec2(b, moveX, moveZ);
        return b;
    }

    private void writeBlockActions(ByteBuf b) {
        Bin.writeVarUInt(b, blockActions.size());
        for (int[] a : blockActions) {
            Bin.writeVarInt(b, a[0]);
            Bin.writeBlockPos(b, a[1], a[2], a[3]);
            Bin.writeVarInt(b, a[4]);
        }
    }

    /** CameraOrientation: the unit vector the camera looks along, from yaw and pitch in degrees. */
    private void writeCamera(ByteBuf b) {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        float cx = (float) (-Math.sin(yr) * Math.cos(pr));
        float cy = (float) -Math.sin(pr);
        float cz = (float) (Math.cos(yr) * Math.cos(pr));
        Bin.writeVec3(b, cx, cy, cz);
    }
}
