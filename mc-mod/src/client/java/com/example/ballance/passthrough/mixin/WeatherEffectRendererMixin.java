package com.example.ballance.passthrough.mixin;

import com.example.ballance.passthrough.PassthroughScene;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 去掉雨雪。虚空世界里天气没有意义，而且它同样是天空之外的一条不透明 pass。
 */
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void ballancePassthrough$hideWeather(CallbackInfo callback) {
        if (PassthroughScene.hideSky()) {
            callback.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void ballancePassthrough$hideWeatherOit(CallbackInfo callback) {
        if (PassthroughScene.hideSky()) {
            callback.cancel();
        }
    }
}
