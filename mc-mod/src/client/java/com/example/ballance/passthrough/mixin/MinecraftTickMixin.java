package com.example.ballance.passthrough.mixin;

import com.example.ballance.passthrough.CameraSync;
import com.example.ballance.passthrough.McWorldAutoload;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client tick hook for the M1 camera sync: moves the player onto the ball so chunks keep loading
 * around the viewpoint. The camera itself is not touched here -- it is per frame, not per tick,
 * and belongs in {@link CameraMixin}.
 */
@Mixin(Minecraft.class)
public class MinecraftTickMixin {
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void ballancePassthrough$syncPlayer(CallbackInfo callback) {
        final Minecraft client = (Minecraft) (Object) this;
        McWorldAutoload.onClientTick(client);
        CameraSync.onClientTick(client);
    }
}
