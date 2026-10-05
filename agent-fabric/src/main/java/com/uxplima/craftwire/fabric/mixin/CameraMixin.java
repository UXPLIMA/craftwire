package com.uxplima.craftwire.fabric.mixin;

import com.uxplima.craftwire.fabric.camera.CameraOverride;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean detached;

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "update", at = @At("TAIL"))
    private void craftwire$applyOverride(DeltaTracker deltaTracker, CallbackInfo ci) {
        CameraOverride.Pose p = CameraOverride.INSTANCE.get();
        if (p == null) return;
        setPosition(p.x(), p.y(), p.z());
        setRotation(p.yaw(), p.pitch());
        detached = true;   // render the local player's body, like third person
    }
}
