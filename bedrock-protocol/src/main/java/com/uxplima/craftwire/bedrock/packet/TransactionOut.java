package com.uxplima.craftwire.bedrock.packet;

import static com.uxplima.craftwire.bedrock.packet.LoginOut.start;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;

/**
 * InventoryTransaction packets: using the held item on a block or in the air, on an entity (attack, interact), and
 * releasing it (bows, food). Inventory moves go through ItemStackRequest instead (StackRequest).
 */
public final class TransactionOut {
    private static final int TYPE_USE_ITEM = 2;
    private static final int TYPE_USE_ITEM_ON_ENTITY = 3;
    private static final int TYPE_RELEASE_ITEM = 4;

    public static final int ENTITY_INTERACT = 0;
    public static final int ENTITY_ATTACK = 1;

    private TransactionOut() {}

    /** The common head: legacy request id 0, no legacy slots, the transaction type, no inventory actions. */
    private static ByteBuf head(int type) {
        ByteBuf b = start(PacketIds.INVENTORY_TRANSACTION);
        Bin.writeVarInt(b, 0);
        Bin.writeBool(b, false);
        Bin.writeVarUInt(b, type);
        Bin.writeVarUInt(b, 0);
        return b;
    }

    public static ByteBuf useItem(ItemUse use, int shieldId) {
        ByteBuf b = head(TYPE_USE_ITEM);
        use.write(b, shieldId);
        return b;
    }

    /**
     * UseItemOnEntity: `action` ENTITY_INTERACT or ENTITY_ATTACK, from the bot's position at the target's, with the
     * main hand (hand 0, a field from 1.26.60 on).
     */
    public static ByteBuf onEntity(long targetRuntimeId, int action, int hotbar, BedrockItem held, float[] botPos, float[] clickPos,
                                   int shieldId, BedrockVersion v) {
        ByteBuf b = head(TYPE_USE_ITEM_ON_ENTITY);
        Bin.writeVarULong(b, targetRuntimeId);
        Bin.writeVarInt(b, action);
        Bin.writeVarInt(b, hotbar);
        if (v.has1_26_60()) b.writeByte(0);
        held.write(b, shieldId);
        Bin.writeVec3(b, botPos[0], botPos[1], botPos[2]);
        Bin.writeVec3(b, clickPos[0], clickPos[1], clickPos[2]);
        return b;
    }

    /** ReleaseItem action 0 (release a drawn bow or trident), 1 (finish eating or drinking), main hand from 1.26.60. */
    public static ByteBuf release(int action, int hotbar, BedrockItem held, float[] headPos, int shieldId, BedrockVersion v) {
        ByteBuf b = head(TYPE_RELEASE_ITEM);
        Bin.writeVarInt(b, action);
        Bin.writeVarInt(b, hotbar);
        held.write(b, shieldId);
        Bin.writeVec3(b, headPos[0], headPos[1], headPos[2]);
        if (v.has1_26_60()) b.writeByte(0);
        return b;
    }
}
