package com.example.ballance.passthrough.mixin;

import com.example.ballance.passthrough.CameraSync;
import com.example.ballance.passthrough.McCameraChannel;
import com.example.ballance.passthrough.McCameraMapper;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Installs the Ballance viewpoint into the Minecraft camera (milestone M1).
 *
 * <p>{@code Camera.update} calls {@code alignWithEntity} right after it takes the entity the
 * camera rides on, and then derives everything else from the transform that method left behind:
 * the cull frustum is prepared from {@code position} and the rotation, the projection is built
 * from the FOV, and {@code extractRenderState} copies position/rotation into the render state the
 * level renderer consumes. Overriding at the tail of {@code alignWithEntity} therefore lands in
 * the one place where a single write reaches all three.
 *
 * <p>The FOV is taken over in {@code calculateFov} rather than after it, because the projection
 * matrix -- built later in the same {@code update} -- has to see the Ballance FOV too. Otherwise
 * the frustum would be culled with Minecraft's FOV while the frame is drawn with Ballance's, and
 * chunks at the edges would pop.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
    private void ballancePassthrough$applyCameraPose(float partialTick, CallbackInfo callback) {
        if (!CameraSync.enabled()) {
            return;
        }
        CameraSync.refreshForFrame();
        final McCameraChannel.Pose pose = CameraSync.framePose();
        if (pose == null || !pose.valid()) {
            return;
        }
        McCameraMapper.ensure(pose);
        final double[] eye = McCameraMapper.toBlocks(pose.eye());
        final float[] yawPitch = McCameraMapper.yawPitch(pose.forward());
        setPosition(eye[0], eye[1], eye[2]);
        setRotation(yawPitch[0], yawPitch[1]);
    }

    @Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true)
    private void ballancePassthrough$applyCameraFov(float partialTick, CallbackInfoReturnable<Float> callback) {
        final McCameraChannel.Pose pose = CameraSync.framePose();
        if (pose == null || !pose.valid()) {
            return;
        }
        callback.setReturnValue((float) Math.toDegrees(pose.fovY()));
    }
}
