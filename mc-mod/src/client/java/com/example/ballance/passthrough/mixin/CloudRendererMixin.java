package com.example.ballance.passthrough.mixin;

import com.example.ballance.passthrough.PassthroughScene;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 去掉云。云不在天空 pass 里，{@code LevelRenderer.addMainPass} 单独画一遍，
 * 所以掐天空掐不掉它。两条渲染路径（普通 / OIT）都要掐。
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ballancePassthrough$hideClouds(CallbackInfo callback) {
        if (PassthroughScene.hideSky()) {
            callback.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void ballancePassthrough$hideCloudsOit(CallbackInfo callback) {
        if (PassthroughScene.hideSky()) {
            callback.cancel();
        }
    }
}
