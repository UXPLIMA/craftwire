package com.uxplima.craftwire.fabric.compat;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies a version's compat mixins (package ...compat.v26_3.mixin) only when that version's code was selected. */
public final class VersionMixinPlugin implements IMixinConfigPlugin {
    private boolean active;

    @Override
    public void onLoad(String mixinPackage) {
        String[] parts = mixinPackage.split("\\.");
        active = parts.length >= 2 && parts[parts.length - 2].equals(Versions.id(Versions.selected()));
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return active;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
