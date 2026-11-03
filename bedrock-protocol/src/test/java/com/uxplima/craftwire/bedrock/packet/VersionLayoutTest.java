package com.uxplima.craftwire.bedrock.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.bedrock.codec.Bin;
import com.uxplima.craftwire.bedrock.codec.Nbt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The packets that differ between 1.26.50 and 1.26.60, built field by field in the order gophertunnel's Marshal
 * methods write them (0e179fc for 1.26.60, the #541 diff for what 1.26.50 had), then read or compared with ours.
 */
class VersionLayoutTest {
    @ParameterizedTest
    @EnumSource(BedrockVersion.class)
    void startGameReadsToTheBlockIdModeInEitherVersion(BedrockVersion v) {
        StartGame s = (StartGame) PacketDecoder.decode(startGame(v), v);
        assertEquals(42, s.runtimeId());
        assertEquals(1, s.dimension());
        assertEquals(2, s.worldGameMode());
        assertTrue(s.serverAuthBlockBreaking());
        assertEquals(List.of("geyser:custom"), s.customBlocks().stream().map(StartGame.CustomBlock::name).toList());
        assertTrue(s.serverAuthInventory());
        assertEquals(v.game(), s.gameVersion());
        assertTrue(s.hashedBlockIds());
    }

    @ParameterizedTest
    @EnumSource(BedrockVersion.class)
    void playerListSkipsEachEntryWholeInEitherVersion(BedrockVersion v) {
        ByteBuf b = header(PacketIds.PLAYER_LIST);
        Bin.writeVarUInt(b, 2);
        entry(b, v, "Steve", "2535400000000001");
        entry(b, v, "Alex", "2535400000000002");
        PlayerListPacket p = (PlayerListPacket) PacketDecoder.decode(b, v);
        assertTrue(p.add());
        assertEquals(List.of("Steve", "Alex"), p.entries().stream().map(PlayerListPacket.Entry::name).toList());
        assertEquals("2535400000000002", p.entries().get(1).xuid());
        assertFalse(b.isReadable(), "the whole packet was read");
    }

    @Test
    void swingCarriesTheHandFrom1_26_60On() {
        ByteBuf old = header(PacketIds.ANIMATE);
        old.writeByte(1);
        Bin.writeVarULong(old, 7);
        old.writeFloatLE(0);
        Bin.writeBool(old, false);
        ByteBuf now = old.copy();
        now.writeByte(0);
        assertEquals(hex(old), hex(PlayOut.swing(7, BedrockVersion.V1_26_50)));
        assertEquals(hex(now), hex(PlayOut.swing(7, BedrockVersion.V1_26_60)));
    }

    @Test
    void useItemOnEntityCarriesTheHandAfterTheHotbarSlotFrom1_26_60On() {
        float[] pos = {1, 2, 3};
        String old = hex(TransactionOut.onEntity(9, TransactionOut.ENTITY_ATTACK, 4, BedrockItem.EMPTY, pos, pos, 0, BedrockVersion.V1_26_50));
        String now = hex(TransactionOut.onEntity(9, TransactionOut.ENTITY_ATTACK, 4, BedrockItem.EMPTY, pos, pos, 0, BedrockVersion.V1_26_60));
        // Same packet but one byte: TargetEntityRuntimeID 9, ActionType 1 and HotBarSlot 4 (zig-zag 08), then Hand 00.
        assertEquals(old.length() + 2, now.length());
        int at = now.indexOf("090208") + 6;
        assertEquals(now.substring(0, at) + now.substring(at + 2), old);
        assertEquals("00", now.substring(at, at + 2));
    }

    @Test
    void theProtocolNumberGoesBigEndianIntoRequestNetworkSettingsAndLogin() {
        ByteBuf r = LoginOut.requestNetworkSettings(BedrockVersion.V1_26_50);
        Bin.readVarUInt(r);
        assertEquals(2193, r.readInt());
        ByteBuf l = LoginOut.login(new byte[0], BedrockVersion.V1_26_60);
        Bin.readVarUInt(l);
        assertEquals(2225, l.readInt());
    }

    private static ByteBuf header(int id) {
        ByteBuf b = Unpooled.buffer();
        Bin.writeVarUInt(b, id);
        return b;
    }

    private static String hex(ByteBuf b) {
        return ByteBufUtil.hexDump(b);
    }

    /** PlayerListEntry with the add variant; PlayFabID sits in the entry from 1.26.60 and in the skin before. */
    private static void entry(ByteBuf b, BedrockVersion v, String name, String xuid) {
        Bin.writeVarUInt(b, 1);
        b.writeByte(0);
        Bin.writeUuid(b, UUID.nameUUIDFromBytes(name.getBytes()));
        Bin.writeVarLong(b, -5);
        Bin.writeString(b, name);
        Bin.writeString(b, xuid);
        if (v.has1_26_60()) Bin.writeString(b, "playfab-" + name);
        Bin.writeString(b, "");
        b.writeIntLE(7);
        skin(b, v, name);
        b.writeBytes(new byte[] {0, 0, 0});
        b.writeInt(0xff112233);
    }

    private static void skin(ByteBuf b, BedrockVersion v, String name) {
        Bin.writeString(b, "skin-" + name);
        if (!v.has1_26_60()) Bin.writeString(b, "playfab-" + name);
        Bin.writeBytes(b, "{\"geometry\":{}}".getBytes());
        b.writeIntLE(2);
        b.writeIntLE(1);
        Bin.writeBytes(b, new byte[8]);
        Bin.writeVarUInt(b, 1);
        b.writeIntLE(1);
        b.writeIntLE(1);
        Bin.writeBytes(b, new byte[4]);
        Bin.writeVarUInt(b, 1);
        b.writeFloatLE(3);
        Bin.writeVarUInt(b, 0);
        b.writeIntLE(0);
        b.writeIntLE(0);
        Bin.writeBytes(b, new byte[0]);
        Bin.writeBytes(b, "{}".getBytes());
        Bin.writeBytes(b, "1.0.0".getBytes());
        Bin.writeBytes(b, new byte[0]);
        Bin.writeString(b, "");
        Bin.writeString(b, "full-" + name);
        b.writeByte(0);
        b.writeInt(0xff000000);
        Bin.writeVarUInt(b, 1);
        Bin.writeString(b, "piece");
        b.writeIntLE(0);
        Bin.writeUuid(b, UUID.randomUUID());
        Bin.writeBool(b, true);
        Bin.writeString(b, "product");
        Bin.writeVarUInt(b, 1);
        Bin.writeString(b, "persona_eyes");
        for (int i = 0; i < 4; i++) b.writeInt(0xff0000ff);
        b.writeBytes(new byte[5]);
        Bin.writeString(b, "false");
        Bin.writeString(b, "hash");
    }

    /** StartGame up to the fields after UseBlockNetworkIDHashes, as gophertunnel's StartGame.Marshal writes it. */
    private static ByteBuf startGame(BedrockVersion v) {
        ByteBuf b = header(PacketIds.START_GAME);
        Bin.writeVarLong(b, -3);
        Bin.writeVarULong(b, 42);
        Bin.writeVarInt(b, 1);
        Bin.writeVec3(b, 0.5f, 65, 0.5f);
        b.writeFloatLE(0);
        b.writeFloatLE(90);
        b.writeLongLE(123456789L);
        b.writeShortLE(0);
        Bin.writeString(b, "plains");
        Bin.writeVarInt(b, 1);
        Bin.writeVarInt(b, 1);
        Bin.writeVarInt(b, 2);
        levelSettings(b, v);
        Bin.writeBool(b, false);
        Bin.writeVarInt(b, 40);
        Bin.writeBool(b, true);
        b.writeLongLE(6000);
        Bin.writeVarInt(b, 0);
        Bin.writeVarUInt(b, 1);
        Bin.writeString(b, "geyser:custom");
        Nbt.writeEmptyCompound(b);
        Bin.writeString(b, "correlation");
        Bin.writeBool(b, true);
        Bin.writeString(b, v.game());
        Nbt.writeEmptyCompound(b);
        b.writeLongLE(0);
        Bin.writeUuid(b, new UUID(0, 0));
        Bin.writeBool(b, false);
        Bin.writeBool(b, true);
        Bin.writeBool(b, false);
        Bin.writeBool(b, false);
        for (int i = 0; i < 4; i++) Bin.writeString(b, "");
        return b;
    }

    /** From Hardcore to Trial; EditorLevelMigrationVersion (int8) only from 1.26.60. */
    private static void levelSettings(ByteBuf b, BedrockVersion v) {
        Bin.writeBool(b, false);
        Bin.writeVarInt(b, 2);
        Bin.writeBlockPos(b, 0, 64, 0);
        Bin.writeBool(b, false);
        Bin.writeVarInt(b, 0);
        Bin.writeBool(b, false);
        Bin.writeBool(b, false);
        if (v.has1_26_60()) b.writeByte(1);
        Bin.writeVarInt(b, -1);
        Bin.writeVarUInt(b, 0);
        Bin.writeBool(b, false);
        Bin.writeString(b, "");
        b.writeFloatLE(0);
        b.writeFloatLE(0);
        b.writeBytes(new byte[] {0, 1, 1});
        Bin.writeVarInt(b, 4);
        Bin.writeVarInt(b, 4);
        b.writeBytes(new byte[] {1, 0});
        Bin.writeVarUInt(b, 2);
        Bin.writeString(b, "dodaylightcycle");
        Bin.writeBool(b, true);
        Bin.writeVarUInt(b, 1);
        Bin.writeBool(b, true);
        Bin.writeString(b, "randomtickspeed");
        Bin.writeBool(b, true);
        Bin.writeVarUInt(b, 2);
        b.writeIntLE(1);
        b.writeIntLE(1);
        Bin.writeString(b, "data_driven_items");
        Bin.writeBool(b, true);
        b.writeBytes(new byte[] {0, 0, 0, 1});
        b.writeIntLE(4);
        b.writeBytes(new byte[10]);
        Bin.writeString(b, "*");
        b.writeIntLE(0);
        b.writeIntLE(0);
        Bin.writeBool(b, true);
        Bin.writeString(b, "");
        Bin.writeString(b, "");
        Bin.writeBool(b, true);
        Bin.writeBool(b, false);
        b.writeByte(0);
        Bin.writeBool(b, false);
        Bin.writeVarInt(b, 0);
        Bin.writeBool(b, false);
        Bin.writeString(b, "level");
        Bin.writeString(b, "World");
        Bin.writeString(b, "");
    }
}
