package com.example.ballance.passthrough.mixin;

import com.example.ballance.passthrough.CameraSync;
import com.example.ballance.passthrough.LevelSession;
import com.example.ballance.passthrough.LevelVoxels;
import com.example.ballance.passthrough.McWorldBootstrap;
import com.example.ballance.passthrough.PassthroughInput;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client tick hook: opens the void world (M2a), then moves the player onto the ball so chunks
 * keep loading around the viewpoint (M1). The camera itself is not touched here -- it is per
 * frame, not per tick, and belongs in {@link CameraMixin}.
 */
@Mixin(Minecraft.class)
public class MinecraftTickMixin {
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void ballancePassthrough$tick(CallbackInfo callback) {
        final Minecraft client = (Minecraft) (Object) this;
        McWorldBootstrap.onClientTick(client);
        // One world per level: watches the camera channel for a level starting and replaces
        // the world when the level being left had been built in (2026-11-07).
        LevelSession.onClientTick(client);
        CameraSync.onClientTick(client);
        // The level's own surfaces, imported as invisible barrier blocks so that vanilla
        // placement (redstone, rails, torches) has something to stand on (2026-11-08).
        LevelVoxels.onClientTick(client);
        // M1b: the E key, the pointer and the wheel, all arriving through the
        // Ballance camera channel (this window never sees them otherwise).
        PassthroughInput.onClientTick(client);
    }
}
