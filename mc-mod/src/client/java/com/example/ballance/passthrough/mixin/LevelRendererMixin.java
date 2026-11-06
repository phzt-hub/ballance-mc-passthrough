package com.example.ballance.passthrough.mixin;

import com.example.ballance.passthrough.PassthroughScene;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 去掉天空（M2a）。
 *
 * <p>{@code LevelRenderer.render} 只在 {@code shouldRenderSky} 为真时调 {@code addSkyPass}，
 * 而那个 pass 画的是天穹、太阳、月亮和星星——三个不透明的东西，会把本来 alpha=0 的清屏
 * 铺成不透明的天。掐掉 {@code addSkyPass} 就一次去干净，连 {@code skybox != NONE} 的分支
 * 都不用管。
 *
 * <p>清屏色本身不用动：同一个方法里已经是 {@code new Vector4f(fogColor.x, fogColor.y,
 * fogColor.z, 0.0F)}（26.3 反编译所见），alpha 本来就是 0。
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
    private void ballancePassthrough$hideSky(CallbackInfo callback) {
        if (PassthroughScene.hideSky()) {
            callback.cancel();
        }
    }
}
