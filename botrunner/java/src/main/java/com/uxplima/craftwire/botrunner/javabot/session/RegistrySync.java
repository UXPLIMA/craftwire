package com.uxplima.craftwire.botrunner.javabot.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.tags.TagNetworkSerialization;

/**
 * The registries a server sends in the configuration phase, turned into the RegistryAccess the play protocol decodes
 * with (biomes in chunks, item components, chat types). The bot announces no known packs, so the server sends every
 * entry in full and nothing has to come from local data packs. Netty event loop of one connection.
 */
final class RegistrySync {
    private static final Object TAG_LOCK = new Object();
    private static boolean staticTagsBound;

    private final Map<ResourceKey<? extends Registry<?>>, List<RegistrySynchronization.PackedRegistryEntry>> entries = new HashMap<>();
    private final Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = new HashMap<>();

    void data(ClientboundRegistryDataPacket p) {
        entries.computeIfAbsent(p.registry(), k -> new ArrayList<>()).addAll(p.entries());
    }

    void tags(ClientboundUpdateTagsPacket p) {
        tags.putAll(p.getTags());
    }

    /** The static registries of the server jar plus the synced ones. */
    RegistryAccess.Frozen build() {
        bindStaticTags();
        Map<ResourceKey<? extends Registry<?>>, RegistryDataLoader.NetworkedRegistryData> data = new HashMap<>();
        entries.forEach((key, list) -> data.put(key,
                new RegistryDataLoader.NetworkedRegistryData(list, tags.getOrDefault(key, TagNetworkSerialization.NetworkPayload.EMPTY))));
        RegistryAccess.Frozen statics = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RegistryAccess.Frozen synced = RegistryDataLoader.load(data, ResourceProvider.EMPTY, statics.listRegistries().toList(),
                RegistryDataLoader.SYNCHRONIZED_REGISTRIES, Runnable::run).join();
        return new RegistryAccess.ImmutableRegistryAccess(Stream.concat(statics.registries(), synced.registries())).freeze();
    }

    /**
     * Item components and recipes can name block and item tags, which decode only from bound tags. The server jar's
     * registries are frozen without them (no data pack is loaded), so the first server's tags are bound once per
     * version; later servers almost always send the same vanilla tags.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void bindStaticTags() {
        synchronized (TAG_LOCK) {
            if (staticTagsBound || tags.isEmpty()) return;
            for (var e : tags.entrySet()) {
                Registry<?> registry = BuiltInRegistries.REGISTRY.getValue((ResourceKey) e.getKey());
                if (registry == null || entries.containsKey(e.getKey())) continue;
                bind((Registry) registry, e.getValue());
            }
            staticTagsBound = true;
        }
    }

    private static <T> void bind(Registry<T> registry, TagNetworkSerialization.NetworkPayload payload) {
        registry.prepareTagReload(payload.resolve(registry)).apply();
    }
}
