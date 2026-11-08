package com.example.ballance.passthrough.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Minecraft rendering at speed while its window is minimised (2026-11-08).
 *
 * <p>In passthrough mode the Minecraft window is supposed to be out of the way: the Ballance
 * window is in front with the frame drawn into it. Vanilla reads that as an iconified window
 * and drops to {@code ICONIFIED_WINDOW_LIMIT} (10 fps, and measured 5/s once the failed present
 * is counted) -- which is exactly the frame rate the composite then runs at. Measured on
 * 2026-11-08 14:58-15:02: publishes of 25/s while the window was merely behind, 5/s for as long
 * as it was minimised, and the Ballance side consuming 4-5/s to match.
 *
 * <p>Only the iconified branch is lifted. The AFK limits and the player's own frame rate setting
 * are left alone, and {@code BALLANCE_MC_ICONIFIED_FPS} (default 60) or
 * {@code BALLANCE_MC_EXPORT=0} turn it off.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
    private static final boolean ENABLED = !"0".equals(System.getenv("BALLANCE_MC_EXPORT"));

    private static final int MINIMISED_FPS = readInt("BALLANCE_MC_ICONIFIED_FPS", 60);

    @Inject(method = "getFramerateLimit()I", at = @At("HEAD"), cancellable = true)
    private void ballancePassthrough$noIconifiedThrottle(CallbackInfoReturnable<Integer> callback) {
        if (!ENABLED || MINIMISED_FPS <= 0) {
            return;
        }
        final FramerateLimitTracker self = (FramerateLimitTracker) (Object) this;
        if (self.getThrottleReason()
                == FramerateLimitTracker.FramerateThrottleReason.WINDOW_ICONIFIED) {
            callback.setReturnValue(MINIMISED_FPS);
        }
    }

    private static int readInt(String name, int fallback) {
        try {
            final String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
