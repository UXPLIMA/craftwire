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

    /**
     * Right after the camera follows its entity, before update() builds the culling frustum from the camera pose.
     * Applying the pose any later leaves terrain culled from the player's view: vanilla and Sodium both cull with
     * that frustum, so everything behind the player would be missing from override shots.
     */
    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
    private void craftwire$applyOverride(DeltaTracker deltaTracker, CallbackInfo ci) {
        CameraOverride.Pose p = CameraOverride.INSTANCE.get();
        if (p == null) return;
        setPosition(p.x(), p.y(), p.z());
        setRotation(p.yaw(), p.pitch());
        detached = true;   // render the local player's body, like third person
    }
}
