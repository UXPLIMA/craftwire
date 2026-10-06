package com.uxplima.craftwire.core.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Maps a class to its owner: the plugin or mod whose jar (or development folder) holds it, the mod that injected a
 * mixin handler into it, or else a category by package.
 */
public final class OwnerIndex {
    /** Mixin handler names: {@code handler$zbc000$modid$name}, {@code wrapOperation$zza012$modid$name}, … */
    private static final Pattern MIXIN = Pattern.compile("^[A-Za-z]+\\$[a-z]{2,4}\\d{3}\\$([a-z0-9_.-]+)\\$");

    private static final List<Map.Entry<String, Owner>> PACKAGES = List.of(
            Map.entry("net.minecraft.", Owner.MINECRAFT),
            Map.entry("com.mojang.", Owner.MINECRAFT),
            Map.entry("org.bukkit.", Owner.SERVER),
            Map.entry("io.papermc.", Owner.SERVER),
            Map.entry("com.destroystokyo.paper.", Owner.SERVER),
            Map.entry("org.spigotmc.", Owner.SERVER),
            Map.entry("ca.spottedleaf.", Owner.SERVER),
            Map.entry("net.fabricmc.loader.", Owner.LOADER),
            Map.entry("org.spongepowered.asm.", Owner.LOADER),
            Map.entry("com.llamalad7.mixinextras.", Owner.LOADER),
            Map.entry("com.uxplima.craftwire.", Owner.CRAFTWIRE),
            Map.entry("java.", Owner.JAVA),
            Map.entry("javax.", Owner.JAVA),
            Map.entry("jdk.", Owner.JAVA),
            Map.entry("sun.", Owner.JAVA),
            Map.entry("com.sun.", Owner.JAVA));

    private final Map<String, Owner> classes;
    private final Map<String, Owner> mixinIds;
    private final Map<String, Owner> cache = new ConcurrentHashMap<>();

    private OwnerIndex(Map<String, Owner> classes, Map<String, Owner> mixinIds) {
        this.classes = classes;
        this.mixinIds = mixinIds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Owner owner(String className, String method) {
        Matcher m = MIXIN.matcher(method);
        if (m.find()) {
            Owner injected = mixinIds.get(m.group(1));
            if (injected != null) return injected;
        }
        return cache.computeIfAbsent(className, this::ownerOfClass);
    }

    private Owner ownerOfClass(String className) {
        // Lambdas and hidden classes: com.x.Y$$Lambda/0x…, com.x.Y$$Lambda$12 → com.x.Y
        int hidden = className.indexOf("$$");
        String name = hidden > 0 ? className.substring(0, hidden) : className;
        int slash = name.indexOf('/');
        if (slash > 0) name = name.substring(0, slash);
        Owner o = classes.get(name);
        if (o != null) return o;
        for (Map.Entry<String, Owner> e : PACKAGES) {
            if (name.startsWith(e.getKey())) return e.getValue();
        }
        return Owner.LIBRARY;
    }

    public static final class Builder {
        private final Map<String, Owner> classes = new HashMap<>();
        private final Map<String, Owner> mixinIds = new HashMap<>();

        /** Every class in a jar, or under a folder of class files. Unreadable paths are skipped. */
        public Builder add(Owner owner, Path jarOrFolder) {
            try {
                if (Files.isDirectory(jarOrFolder)) {
                    try (Stream<Path> files = Files.walk(jarOrFolder)) {
                        files.filter(f -> f.toString().endsWith(".class")).forEach(f -> {
                            String rel = jarOrFolder.relativize(f).toString().replace('\\', '/');
                            put(owner, rel);
                        });
                    }
                } else if (Files.isRegularFile(jarOrFolder)) {
                    try (ZipFile zip = new ZipFile(jarOrFolder.toFile())) {
                        for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                            String n = en.nextElement().getName();
                            if (n.endsWith(".class") && !n.startsWith("META-INF/")) put(owner, n);
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // a jar that cannot be read leaves its classes to the package rules
            }
            return this;
        }

        public Builder addClasses(Owner owner, Collection<String> classNames) {
            for (String c : classNames) classes.putIfAbsent(c, owner);
            return this;
        }

        /** The id a mod's mixin handlers carry in their names. */
        public Builder mixinId(String id, Owner owner) {
            mixinIds.put(id, owner);
            return this;
        }

        private void put(Owner owner, String path) {
            String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
            if (name.endsWith("module-info") || name.endsWith("package-info")) return;
            classes.putIfAbsent(name, owner);
        }

        public OwnerIndex build() {
            return new OwnerIndex(Map.copyOf(classes), Map.copyOf(mixinIds));
        }
    }
}
