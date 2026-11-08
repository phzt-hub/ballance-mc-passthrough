package com.example.ballance.passthrough.mixin;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the Minecraft player one block big: a 1 x 1 x 1 collision box (2026-11-08).
 *
 * <p>The player stands where the marble is, so a box that is 0.6 wide and 1.8 tall does not
 * line up with anything in that world: it straddles two cells sideways, it pokes half a block
 * up out of the cell the ball is in, and it makes "the cell the player is in" a question with
 * no answer -- which is what the obstruction test that refuses a placement asks. One block
 * square and one block tall makes the player occupy exactly the cell he stands in.
 *
 * <p>The eye height is <b>not</b> touched (1.62, the vanilla value). The placement ray and the
 * crosshair projection both start at {@code getEyeHeight()}, so a shorter eye would move every
 * block a player places down by 1.12 blocks -- a change to aiming, not to collision. Only the
 * box changes.
 *
 * <p>Both sides see it: the integrated server is the same JVM and the same class, so the
 * server's copy of the player has the same box as the client's -- a mismatch there is how a
 * placement ends up refused by the server and taken back by the client.
 *
 * <p>{@code BALLANCE_MC_PLAYER_BOX=0} restores the vanilla 0.6 x 1.8.
 */
@Mixin(LivingEntity.class)
public abstract class PlayerBoxMixin {
    private static final boolean ENABLED = !"0".equals(System.getenv("BALLANCE_MC_PLAYER_BOX"));

    /** One block across, both ways. */
    private static final float WIDTH = 1.0f;

    /** One block tall, so the box is the cell under the feet. */
    private static final float HEIGHT = 1.0f;

    /** Vanilla's standing eye height; see the class comment for why it does not follow. */
    private static final float EYE_HEIGHT = 1.62f;

    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-box");

    private static boolean announced;

    /** Says the box is in force once, so a log can answer "did it take" without a screenshot. */
    private static void announce() {
        if (announced) {
            return;
        }
        announced = true;
        LOGGER.info("player box: {} x {} x {} with the vanilla {} eye height"
                        + " (BALLANCE_MC_PLAYER_BOX=0 restores 0.6 x 1.8)",
                WIDTH, HEIGHT, WIDTH, EYE_HEIGHT);
    }

    @Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
    private void ballancePassthrough$oneBlockBox(Pose pose,
                                                 CallbackInfoReturnable<EntityDimensions> callback) {
        if (!ENABLED || !((Object) this instanceof Player)) {
            return;
        }
        final EntityDimensions base = callback.getReturnValue();
        if (base == null) {
            return;
        }
        announce();
        callback.setReturnValue(new EntityDimensions(WIDTH, HEIGHT, EYE_HEIGHT,
                base.attachments(), base.fixed()));
    }
}
