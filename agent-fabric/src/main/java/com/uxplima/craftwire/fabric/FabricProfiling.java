package com.uxplima.craftwire.fabric;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.profile.Owner;
import com.uxplima.craftwire.core.profile.OwnerIndex;
import com.uxplima.craftwire.core.profile.ProfileTools;
import com.uxplima.craftwire.core.profile.Profiler;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;

/**
 * profile and trace on a client: the render thread, owners from every mod's files (Fabric API's modules count as
 * fabric-api), mixin handlers to the mod that injected them, and the frame rate while sampling.
 */
final class FabricProfiling implements ProfileTools.Platform {
    /** Not mods in the sense of "someone's code": their classes go by package (minecraft, java, loader). */
    private static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader", "mixinextras");

    private volatile OwnerIndex index;

    @Override
    public String gameThread() {
        return "Render thread";
    }

    /** Built once: the mods of a running game do not change. */
    @Override
    public OwnerIndex owners() {
        OwnerIndex i = index;
        if (i != null) return i;
        synchronized (this) {
            if (index == null) index = build();
            return index;
        }
    }

    private static OwnerIndex build() {
        OwnerIndex.Builder b = OwnerIndex.builder();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (BUILT_IN.contains(id)) continue;
            Owner owner = owner(mod);
            for (Path root : mod.getRootPaths()) b.add(owner, root);
            b.mixinId(id, owner);
        }
        return b.build();
    }

    /** A nested mod (a Fabric API module) counts as the mod that ships it. */
    private static Owner owner(ModContainer mod) {
        ModContainer top = mod;
        while (top.getContainingMod().isPresent()) top = top.getContainingMod().get();
        String id = top.getMetadata().getId();
        if (id.equals("craftwire-agent")) return Owner.CRAFTWIRE;
        return new Owner(id, Owner.MOD);
    }

    @Override
    public ProfileTools.Feed feed(Profiler.Session session) {
        ScheduledExecutorService fps = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "craftwire-fps");
            t.setDaemon(true);
            return t;
        });
        long[] sum = {0};
        int[] n = {0};
        int[] min = {Integer.MAX_VALUE};
        fps.scheduleAtFixedRate(() -> {
            int f = Minecraft.getInstance().getFps();
            synchronized (sum) {
                sum[0] += f;
                n[0]++;
                min[0] = Math.min(min[0], f);
            }
        }, 1000, 500, TimeUnit.MILLISECONDS);
        return report -> {
            fps.shutdownNow();
            synchronized (sum) {
                if (n[0] == 0) return;
                JsonObject o = new JsonObject();
                o.addProperty("avg", Math.round((double) sum[0] / n[0]));
                o.addProperty("min", min[0]);
                report.add("fps", o);
            }
        };
    }
}
