package com.example.ballance.passthrough.mixin;

import com.example.ballance.passthrough.McFrameExport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the tail of {@link Minecraft#renderFrame(boolean)} — after the world, the
 * interface and every post effect have been drawn into the main render target, and after
 * the window surface has been presented. The readback itself is asynchronous, so this
 * costs one command submission per captured frame rather than a pipeline stall.
 */
@Mixin(Minecraft.class)
public class MinecraftFrameMixin {
    @Inject(method = "renderFrame(Z)V", at = @At("TAIL"))
    private void ballancePassthrough$exportFrame(boolean advanceGameTime, CallbackInfo callback) {
        McFrameExport.onFrameRendered((Minecraft) (Object) this);
    }
}
