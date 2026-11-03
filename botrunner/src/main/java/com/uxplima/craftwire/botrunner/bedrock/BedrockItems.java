package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.packet.BedrockItem;
import com.uxplima.craftwire.bedrock.packet.ItemPackets;
import com.uxplima.craftwire.bot.model.ItemInfo;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The server's item registry (ItemRegistry: name and network id of every item, custom items included) and items in
 * the shape every bot kind reports. Stack limits are not in the protocol, so they follow vanilla by item family.
 * Runner loop thread.
 */
final class BedrockItems {
    private static final Set<String> SINGLE = Set.of("_sword", "_pickaxe", "_axe", "_shovel", "_hoe", "_helmet", "_chestplate",
            "_leggings", "_boots", "_bucket", "_boat", "_minecart", "_spear");
    private static final Set<String> SINGLE_NAMES = Set.of("minecraft:bow", "minecraft:crossbow", "minecraft:trident", "minecraft:shield",
            "minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion", "minecraft:elytra", "minecraft:fishing_rod",
            "minecraft:flint_and_steel", "minecraft:shears", "minecraft:saddle", "minecraft:totem_of_undying", "minecraft:mace",
            "minecraft:enchanted_book", "minecraft:writable_book", "minecraft:written_book", "minecraft:cake", "minecraft:bed");
    private static final Set<String> SIXTEEN = Set.of("minecraft:ender_pearl", "minecraft:snowball", "minecraft:egg", "minecraft:bucket",
            "minecraft:honey_bottle", "minecraft:armor_stand", "minecraft:banner", "minecraft:oak_sign");

    private final Map<Integer, String> names = new HashMap<>();
    private final Map<String, Integer> ids = new HashMap<>();
    private int shieldId = -1;

    void registry(ItemPackets.ItemRegistry r) {
        for (ItemPackets.ItemEntry e : r.items()) {
            names.put(e.networkId(), e.name());
            ids.put(e.name(), e.networkId());
        }
        shieldId = ids.getOrDefault("minecraft:shield", -1);
    }

    /** The shield's network id: shields carry an extra field (blocking tick) in their item data. */
    int shieldId() {
        return shieldId;
    }

    String name(int networkId) {
        return names.getOrDefault(networkId, "minecraft:unknown");
    }

    int id(String name) {
        String n = name.contains(":") ? name : "minecraft:" + name;
        return ids.getOrDefault(n, -1);
    }

    /** The item as bots report it, or null for an empty slot. */
    ItemInfo info(BedrockItem it) {
        if (it == null || it.empty()) return null;
        String id = name(it.networkId());
        String custom = it.customName();
        return new ItemInfo(id, it.count(), custom != null ? BedrockHud.plain(custom) : display(id), it.lore(), maxStack(id));
    }

    /** minecraft:diamond_sword to "Diamond Sword", the English name the client would show. */
    static String display(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        StringBuilder sb = new StringBuilder();
        for (String w : path.split("_")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return sb.toString();
    }

    static int maxStack(String id) {
        if (SINGLE_NAMES.contains(id)) return 1;
        for (String s : SINGLE) if (id.endsWith(s)) return 1;
        if (SIXTEEN.contains(id) || id.endsWith("_sign") || id.endsWith("_banner")) return 16;
        return 64;
    }
}
