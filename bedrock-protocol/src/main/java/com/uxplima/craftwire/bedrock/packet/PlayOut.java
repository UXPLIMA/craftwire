package com.uxplima.craftwire.bedrock.packet;

import static com.uxplima.craftwire.bedrock.packet.LoginOut.start;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Packets the bot sends while playing: chat, commands, form and NPC answers, interactions, block actions, respawn. */
public final class PlayOut {
    /** PlayerAction actions the bot uses. */
    public static final int ACTION_START_BREAK = 0;
    public static final int ACTION_ABORT_BREAK = 1;
    public static final int ACTION_STOP_BREAK = 2;
    public static final int ACTION_RESPAWN = 7;
    public static final int ACTION_CREATIVE_DESTROY = 13;
    public static final int ACTION_DIMENSION_CHANGE_DONE = 14;
    public static final int ACTION_CRACK_BREAK = 18;

    private PlayOut() {}

    /** Text type 1 (chat) in the authored-message category, as the vanilla client sends a chat line. */
    public static ByteBuf chat(String source, String message, String xuid) {
        ByteBuf b = start(PacketIds.TEXT);
        Bin.writeBool(b, false);
        b.writeByte(1);
        b.writeByte(1);
        Bin.writeString(b, source);
        Bin.writeString(b, message);
        Bin.writeString(b, xuid);
        Bin.writeString(b, "");
        Bin.writeBool(b, false);
        return b;
    }

    /** CommandRequest: the line with its slash, origin "player" with a fresh request id. */
    public static ByteBuf command(String line, long uniqueId) {
        ByteBuf b = start(PacketIds.COMMAND_REQUEST);
        Bin.writeString(b, line.startsWith("/") ? line : "/" + line);
        Bin.writeString(b, "player");
        Bin.writeUuid(b, UUID.randomUUID());
        Bin.writeString(b, "");
        b.writeLongLE(uniqueId);
        Bin.writeBool(b, false);
        Bin.writeString(b, "latest");
        return b;
    }

    /** ModalFormResponse: `json` is the answer (button index, toggles and values), or null to close the form. */
    public static ByteBuf formResponse(int formId, String json) {
        ByteBuf b = start(PacketIds.MODAL_FORM_RESPONSE);
        Bin.writeVarUInt(b, formId);
        Bin.writeBool(b, json != null);
        if (json != null) Bin.writeBytes(b, json.getBytes(StandardCharsets.UTF_8));
        Bin.writeBool(b, json == null);
        if (json == null) b.writeByte(0);
        return b;
    }

    /** ServerSettingsRequest: asks for the server's settings form (the settings screen tab). */
    public static ByteBuf serverSettings() {
        return start(PacketIds.SERVER_SETTINGS_REQUEST);
    }

    /** NPCRequest type 1 (execute action): presses button `index` of the open NPC dialogue. */
    public static ByteBuf npcAction(long runtimeId, int index, String scene) {
        ByteBuf b = start(PacketIds.NPC_REQUEST);
        Bin.writeVarULong(b, runtimeId);
        b.writeByte(1);
        Bin.writeString(b, "");
        b.writeByte(index);
        Bin.writeString(b, scene);
        return b;
    }

    /** Interact: 4 mouse over an entity, 6 open the inventory (target is the bot itself). */
    public static ByteBuf interact(int action, long targetRuntimeId) {
        ByteBuf b = start(PacketIds.INTERACT);
        b.writeByte(action);
        Bin.writeVarULong(b, targetRuntimeId);
        Bin.writeBool(b, false);
        return b;
    }

    /** Animate action 1: swing the main hand (hand 0, a field from 1.26.60 on). */
    public static ByteBuf swing(long runtimeId, BedrockVersion v) {
        ByteBuf b = start(PacketIds.ANIMATE);
        b.writeByte(1);
        Bin.writeVarULong(b, runtimeId);
        b.writeFloatLE(0);
        Bin.writeBool(b, false);
        if (v.has1_26_60()) b.writeByte(0);
        return b;
    }

    /** PlayerAction for a block (start, abort, stop breaking, crack) or the bot (respawn, dimension change done). */
    public static ByteBuf playerAction(long runtimeId, int action, int x, int y, int z, int face) {
        ByteBuf b = start(PacketIds.PLAYER_ACTION);
        Bin.writeVarULong(b, runtimeId);
        Bin.writeVarInt(b, action);
        Bin.writeBlockPos(b, x, y, z);
        Bin.writeBlockPos(b, x, y, z);
        Bin.writeVarInt(b, face);
        return b;
    }

    /** Respawn state 2 (client ready): sent after the server's state 1 to come back to life. */
    public static ByteBuf respawnReady(long runtimeId, float x, float y, float z) {
        ByteBuf b = start(PacketIds.RESPAWN);
        Bin.writeVec3(b, x, y, z);
        b.writeByte(2);
        Bin.writeVarULong(b, runtimeId);
        return b;
    }

    public static ByteBuf closeContainer(int window, int type) {
        ByteBuf b = start(PacketIds.CONTAINER_CLOSE);
        b.writeByte(window);
        b.writeByte(type);
        Bin.writeBool(b, false);
        return b;
    }

    /** MobEquipment: selects hotbar slot `slot` holding `item` (window 0, the inventory). */
    public static ByteBuf selectHotbar(long runtimeId, BedrockItem item, int slot, int shieldId) {
        ByteBuf b = start(PacketIds.MOB_EQUIPMENT);
        Bin.writeVarULong(b, runtimeId);
        item.write(b, shieldId);
        b.writeByte(slot);
        b.writeByte(slot);
        b.writeByte(0);
        return b;
    }

    /** SubChunkRequest: the sub-chunks at `offsets` (dx, dy, dz triples) around the base sub-chunk position. */
    public static ByteBuf subChunkRequest(int dimension, int x, int y, int z, byte[] offsets) {
        ByteBuf b = start(PacketIds.SUB_CHUNK_REQUEST);
        Bin.writeVarInt(b, dimension);
        Bin.writeVarUInt(b, offsets.length / 3);
        b.writeBytes(offsets);
        b.writeIntLE(x);
        b.writeIntLE(y);
        b.writeIntLE(z);
        return b;
    }
}
