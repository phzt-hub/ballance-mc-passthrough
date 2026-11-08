package com.example.ballance.passthrough.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rebuilds the player's bounding box as a one-block cube (2026-11-08).
 *
 * <p>{@link PlayerBoxMixin} answers what the dimensions <b>should</b> be, but the box the game
 * actually collides with is the cached field, refreshed only by {@code Entity.refreshDimensions}
 * -- and measured in the dev world, the client player's dimensions are never refreshed after it
 * spawns, so the cached box stayed 0.6 x 1.8. Every position write ({@code setPos}, which is
 * what the camera sync does every tick) rebuilds the box through here instead, so patching this
 * one place makes the box follow the player whatever refreshed it.
 *
 * <p>Same numbers as {@link PlayerBoxMixin}: one block square, one block tall, feet at the
 * entity position. The eye is untouched.
 */
@Mixin(Entity.class)
public abstract class PlayerBoxBoundsMixin {
    private static final boolean ENABLED = !"0".equals(System.getenv("BALLANCE_MC_PLAYER_BOX"));

    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-box");

    private static boolean announced;

    @Shadow
    public abstract Vec3 position();

    @Inject(method = "makeBoundingBox()Lnet/minecraft/world/phys/AABB;",
            at = @At("HEAD"), cancellable = true)
    private void ballancePassthrough$cubeBox(CallbackInfoReturnable<AABB> callback) {
        if (!ENABLED || !((Object) this instanceof Player)) {
            return;
        }
        final Vec3 at = position();
        announce();
        callback.setReturnValue(box(at.x, at.y, at.z));
    }

    @Inject(method = "makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;",
            at = @At("HEAD"), cancellable = true)
    private void ballancePassthrough$cubeBoxAt(Vec3 at, CallbackInfoReturnable<AABB> callback) {
        if (!ENABLED || !((Object) this instanceof Player) || at == null) {
            return;
        }
        announce();
        callback.setReturnValue(box(at.x, at.y, at.z));
    }

    private static AABB box(double x, double y, double z) {
        return new AABB(x - 0.5, y, z - 0.5, x + 0.5, y + 1.0, z + 0.5);
    }

    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        LOGGER.info("player box: 1.0 x 1.0 x 1.0, feet at the player position,"
                + " vanilla 1.62 eye height (BALLANCE_MC_PLAYER_BOX=0 restores 0.6 x 1.8)");
    }
}
