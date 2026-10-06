package com.uxplima.craftwire.core.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnerIndexTest {
    @TempDir Path dir;

    private Path jar(String name, String... classes) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            for (String c : classes) {
                j.putNextEntry(new ZipEntry(c.replace('.', '/') + ".class"));
                j.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void classesInAPluginJarBelongToThePluginShadedLibrariesIncluded() throws IOException {
        Owner shop = new Owner("MyShop", Owner.PLUGIN);
        OwnerIndex index = OwnerIndex.builder()
                .add(shop, jar("shop.jar", "com.example.shop.Shop", "com.example.shop.Shop$Buy", "libs.gson.Gson"))
                .build();
        assertEquals(shop, index.owner("com.example.shop.Shop", "buy"));
        assertEquals(shop, index.owner("com.example.shop.Shop$Buy", "run"));
        assertEquals(shop, index.owner("libs.gson.Gson", "toJson"));
        // Lambdas and hidden classes name their defining class first.
        assertEquals(shop, index.owner("com.example.shop.Shop$$Lambda/0x0000012345", "run"));
    }

    @Test
    void classesInADevelopmentFolderBelongToTheMod() throws IOException {
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes.resolve("com/example/mod"));
        Files.createFile(classes.resolve("com/example/mod/ModMain.class"));
        Owner mod = new Owner("examplemod", Owner.MOD);
        OwnerIndex index = OwnerIndex.builder().add(mod, classes).build();
        assertEquals(mod, index.owner("com.example.mod.ModMain", "init"));
    }

    @Test
    void mixinHandlersBelongToTheModThatInjectedThem() {
        Owner mod = new Owner("Sodium", Owner.MOD);
        OwnerIndex index = OwnerIndex.builder().mixinId("sodium", mod).build();
        assertEquals(mod, index.owner("net.minecraft.client.renderer.LevelRenderer", "handler$zbc000$sodium$onRender"));
        assertEquals(mod, index.owner("net.minecraft.client.Minecraft", "wrapOperation$zza012$sodium$tick"));
        assertEquals(Owner.MINECRAFT, index.owner("net.minecraft.client.Minecraft", "handler$zza000$unknownmod$x"));
    }

    @Test
    void everythingElseByPackage() {
        OwnerIndex index = OwnerIndex.builder().build();
        assertEquals(Owner.MINECRAFT, index.owner("net.minecraft.server.MinecraftServer", "tickServer"));
        assertEquals(Owner.MINECRAFT, index.owner("com.mojang.blaze3d.systems.RenderSystem", "flip"));
        assertEquals(Owner.SERVER, index.owner("org.bukkit.craftbukkit.scheduler.CraftScheduler", "mainThreadHeartbeat"));
        assertEquals(Owner.SERVER, index.owner("io.papermc.paper.threadedregions.EntityScheduler", "executeTick"));
        assertEquals(Owner.SERVER, index.owner("ca.spottedleaf.moonrise.common.util.TickThread", "run"));
        assertEquals(Owner.JAVA, index.owner("java.util.HashMap", "get"));
        assertEquals(Owner.JAVA, index.owner("jdk.internal.misc.Unsafe", "park"));
        assertEquals(Owner.LOADER, index.owner("net.fabricmc.loader.impl.launch.knot.Knot", "launch"));
        assertEquals(Owner.CRAFTWIRE, index.owner("com.uxplima.craftwire.paper.bot.BotManager", "tick"));
        assertEquals(Owner.LIBRARY, index.owner("it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap", "get"));
    }

    @Test
    void pluginsAndModsWinOverPackageRules() throws IOException {
        // A plugin that ships its own copy of a library under a common package.
        Owner p = new Owner("Weird", Owner.PLUGIN);
        OwnerIndex index = OwnerIndex.builder().add(p, jar("w.jar", "it.unimi.dsi.fastutil.Weird")).build();
        assertEquals(p, index.owner("it.unimi.dsi.fastutil.Weird", "x"));
    }
}
