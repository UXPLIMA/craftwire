package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;

/**
 * UseItemTransactionData: a click with the held item on a block (action 0), in the air (1) or a block break (2).
 * `blockRuntimeId` is the clicked block's runtime id; the click position is relative to the block.
 */
public record ItemUse(int action, int x, int y, int z, int face, int hotbar, BedrockItem held, float px, float py, float pz,
                      float clickX, float clickY, float clickZ, int blockRuntimeId) {
    public static final int CLICK_BLOCK = 0;
    public static final int CLICK_AIR = 1;
    public static final int BREAK_BLOCK = 2;

    /** The fields after the legacy request part, trigger 1 (player input) and client prediction 1 (success). */
    void write(ByteBuf b, int shieldId) {
        Bin.writeVarInt(b, action);
        b.writeByte(1);
        Bin.writeBlockPos(b, x, y, z);
        b.writeByte(face);
        Bin.writeVarInt(b, hotbar);
        b.writeByte(0);
        held.write(b, shieldId);
        Bin.writeVec3(b, px, py, pz);
        Bin.writeVec3(b, clickX, clickY, clickZ);
        Bin.writeVarUInt(b, blockRuntimeId);
        b.writeByte(1);
        b.writeByte(0);
    }
}
