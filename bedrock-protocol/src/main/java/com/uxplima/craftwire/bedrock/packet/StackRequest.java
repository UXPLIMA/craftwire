package com.uxplima.craftwire.bedrock.packet;

import static com.uxplima.craftwire.bedrock.packet.LoginOut.start;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;
import java.util.List;

/**
 * One ItemStackRequest: the server-authoritative way to move items. Each action names its slots by container id,
 * slot and the stack network id the server last gave that slot; the server answers with ItemStackResponse.
 */
public record StackRequest(int requestId, List<Action> actions) {
    /** StackRequestSlotInfo: FullContainerName id (no dynamic id), slot, stack network id. */
    public record Slot(int containerId, int slot, int stackId) {
        void write(ByteBuf b) {
            b.writeByte(containerId);
            Bin.writeBool(b, false);
            b.writeByte(slot);
            b.writeIntLE(stackId);
        }
    }

    /** A StackRequestAction; `id()` is its protocol action id. */
    public sealed interface Action {
        int id();

        void body(ByteBuf b, int shieldId);
    }

    public record Take(int count, Slot from, Slot to) implements Action {
        public int id() {
            return 0;
        }

        public void body(ByteBuf b, int shieldId) {
            b.writeByte(count);
            from.write(b);
            to.write(b);
        }
    }

    public record Place(int count, Slot from, Slot to) implements Action {
        public int id() {
            return 1;
        }

        public void body(ByteBuf b, int shieldId) {
            b.writeByte(count);
            from.write(b);
            to.write(b);
        }
    }

    public record Swap(Slot from, Slot to) implements Action {
        public int id() {
            return 2;
        }

        public void body(ByteBuf b, int shieldId) {
            from.write(b);
            to.write(b);
        }
    }

    public record Drop(int count, Slot from) implements Action {
        public int id() {
            return 3;
        }

        public void body(ByteBuf b, int shieldId) {
            b.writeByte(count);
            from.write(b);
            Bin.writeBool(b, false);
        }
    }

    public record Destroy(int count, Slot from) implements Action {
        public int id() {
            return 4;
        }

        public void body(ByteBuf b, int shieldId) {
            b.writeByte(count);
            from.write(b);
        }
    }

    /** Consume: a crafting ingredient used up by the craft before it. */
    public record Consume(int count, Slot from) implements Action {
        public int id() {
            return 5;
        }

        public void body(ByteBuf b, int shieldId) {
            b.writeByte(count);
            from.write(b);
        }
    }

    public record CraftRecipe(int recipeNetworkId, int times) implements Action {
        public int id() {
            return 12;
        }

        public void body(ByteBuf b, int shieldId) {
            Bin.writeVarUInt(b, recipeNetworkId);
            b.writeByte(times);
        }
    }

    public record CraftCreative(int creativeItemNetworkId, int times) implements Action {
        public int id() {
            return 14;
        }

        public void body(ByteBuf b, int shieldId) {
            Bin.writeVarUInt(b, creativeItemNetworkId);
            b.writeByte(times);
        }
    }

    /**
     * CraftResultsDeprecated: the items the craft makes, which the vanilla client still sends after CraftRecipe. Items
     * here use the ItemStack form (varint id, no stack id), not ItemInstance.
     */
    public record CraftResults(List<BedrockItem> results, int times) implements Action {
        public int id() {
            return 20;
        }

        public void body(ByteBuf b, int shieldId) {
            Bin.writeVarUInt(b, results.size());
            for (BedrockItem item : results) {
                Bin.writeVarInt(b, item.networkId());
                b.writeShortLE(item.count());
                Bin.writeVarUInt(b, item.meta());
                Bin.writeVarInt(b, item.blockRuntimeId());
                item.writeUserData(b, shieldId);
            }
            b.writeByte(times);
        }
    }

    /** The request body: id, actions (each a variant, its id byte and body), no filter strings, cause 0. */
    void write(ByteBuf b, int shieldId) {
        Bin.writeVarInt(b, requestId);
        Bin.writeVarUInt(b, actions.size());
        for (Action a : actions) {
            int id = a.id();
            // PlaceInContainer (7) and TakeOutContainer (8) share variants with Place and Take, so later variants are id - 2.
            Bin.writeVarUInt(b, id > 8 ? id - 2 : id);
            b.writeByte(id);
            a.body(b, shieldId);
        }
        Bin.writeVarUInt(b, 0);
        b.writeIntLE(0);
    }

    /** ItemStackRequest packet with these requests. */
    public static ByteBuf packet(List<StackRequest> requests, int shieldId) {
        ByteBuf b = start(PacketIds.ITEM_STACK_REQUEST);
        Bin.writeVarUInt(b, requests.size());
        for (StackRequest r : requests) r.write(b, shieldId);
        return b;
    }
}
