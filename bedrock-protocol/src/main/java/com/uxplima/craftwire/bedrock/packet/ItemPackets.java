package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.Bin;
import com.uxplima.craftwire.bedrock.codec.Nbt;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;

/** Items and containers: the item registry, inventory contents and slots, open containers, stack request answers. */
public final class ItemPackets {
    /** ItemRegistry entry: an item's name and the network id ItemInstance uses for it. */
    public record ItemEntry(String name, int networkId) {}

    public record ItemRegistry(List<ItemEntry> items) implements Clientbound {}

    /** InventoryContent: every slot of window `window`; `containerId` is the FullContainerName id. */
    public record InventoryContent(int window, List<BedrockItem> items, int containerId) implements Clientbound {}

    public record InventorySlot(int window, int slot, BedrockItem item) implements Clientbound {}

    /** ContainerOpen: `type` is the ContainerType (0 container, 1 workbench, 2 furnace, ...); -1 the inventory. */
    public record ContainerOpen(int window, int type, int x, int y, int z, long entityUniqueId) implements Clientbound {}

    public record ContainerClose(int window, int type, boolean serverSide) implements Clientbound {}

    /** ContainerSetData: furnace progress, brewing time and the like, by window. */
    public record ContainerData(int window, int key, int value) implements Clientbound {}

    /** One slot the server confirmed or corrected after an ItemStackRequest. */
    public record SlotUpdate(int containerId, int slot, int count, int stackId, String customName) {}

    /** ItemStackResponse entry: status 0 ok, anything else rejected (the client rolls the request back). */
    public record StackResult(int status, int requestId, List<SlotUpdate> slots) {
        public boolean ok() {
            return status == 0;
        }
    }

    public record StackResponse(List<StackResult> results) implements Clientbound {}

    /** MobEquipment: what an entity (or the bot) holds; `hotbarSlot` is the selected slot. */
    public record Equipment(long runtimeId, BedrockItem item, int inventorySlot, int hotbarSlot, int window) implements Clientbound {}

    private ItemPackets() {}

    static Clientbound decode(int id, ByteBuf b) {
        return switch (id) {
            case PacketIds.ITEM_REGISTRY -> registry(b);
            case PacketIds.INVENTORY_CONTENT -> content(b);
            case PacketIds.INVENTORY_SLOT -> {
                int window = Bin.readVarUInt(b);
                int slot = Bin.readVarUInt(b);
                if (Bin.readBool(b)) containerName(b);
                if (Bin.readBool(b)) BedrockItem.read(b);
                yield new InventorySlot(window, slot, BedrockItem.read(b));
            }
            case PacketIds.CONTAINER_OPEN -> {
                int window = b.readUnsignedByte();
                int type = b.readByte();
                int[] p = Bin.readBlockPos(b);
                yield new ContainerOpen(window, type, p[0], p[1], p[2], Bin.readVarLong(b));
            }
            case PacketIds.CONTAINER_CLOSE -> new ContainerClose(b.readUnsignedByte(), b.readByte(), Bin.readBool(b));
            case PacketIds.CONTAINER_SET_DATA -> new ContainerData(b.readUnsignedByte(), Bin.readVarInt(b), Bin.readVarInt(b));
            case PacketIds.ITEM_STACK_RESPONSE -> stackResponse(b);
            case PacketIds.MOB_EQUIPMENT -> new Equipment(Bin.readVarULong(b), BedrockItem.read(b), b.readUnsignedByte(),
                    b.readUnsignedByte(), b.readUnsignedByte());
            default -> null;
        };
    }

    private static ItemRegistry registry(ByteBuf b) {
        int n = Bin.readVarUInt(b);
        List<ItemEntry> items = new ArrayList<>(Math.min(n, 8192));
        for (int i = 0; i < n; i++) {
            String name = Bin.readString(b);
            int networkId = b.readShortLE();
            Bin.readBool(b);
            Bin.readVarInt(b);
            Nbt.read(b, Nbt.Encoding.NETWORK);
            items.add(new ItemEntry(name, networkId));
        }
        return new ItemRegistry(items);
    }

    private static InventoryContent content(ByteBuf b) {
        int window = Bin.readVarUInt(b);
        int n = Bin.readVarUInt(b);
        List<BedrockItem> items = new ArrayList<>(Math.min(n, 256));
        for (int i = 0; i < n; i++) items.add(BedrockItem.read(b));
        int container = containerName(b);
        BedrockItem.read(b);
        return new InventoryContent(window, items, container);
    }

    /** FullContainerName: a container id byte and an optional uint32 dynamic id (bundles). */
    static int containerName(ByteBuf b) {
        int id = b.readUnsignedByte();
        if (Bin.readBool(b)) b.skipBytes(4);
        return id;
    }

    private static StackResponse stackResponse(ByteBuf b) {
        int n = Bin.readVarUInt(b);
        List<StackResult> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int status = b.readUnsignedByte();
            int requestId = Bin.readVarInt(b);
            List<SlotUpdate> slots = new ArrayList<>();
            if (Bin.readBool(b)) {
                int containers = Bin.readVarUInt(b);
                for (int c = 0; c < containers; c++) {
                    int container = containerName(b);
                    int count = Bin.readVarUInt(b);
                    for (int s = 0; s < count; s++) slots.add(slot(b, container));
                }
            }
            results.add(new StackResult(status, requestId, slots));
        }
        return new StackResponse(results);
    }

    /** StackResponseSlotInfo: slot, hotbar slot, count, optional stack id, custom name, optional filtered name, durability. */
    private static SlotUpdate slot(ByteBuf b, int container) {
        int slot = b.readUnsignedByte();
        b.skipBytes(1);
        int count = b.readUnsignedByte();
        int stackId = Bin.readBool(b) ? Bin.readVarInt(b) : 0;
        String name = Bin.readString(b);
        if (Bin.readBool(b)) Bin.readString(b);
        Bin.readVarInt(b);
        return new SlotUpdate(container, slot, count, stackId, name);
    }
}
