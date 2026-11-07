package com.example.ballance.passthrough.mixin;

import com.example.ballance.passthrough.CameraSync;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the crosshair where the aim will actually put a block (milestone M1b, moved onto the
 * ball in 2026-11-07).
 *
 * <p>The passthrough overlays the Minecraft frame on the Ballance one, and the ball is drawn by
 * Ballance in its own frame. The crosshair used to be pinned to the centre of this frame (plus a
 * vertical lift), which is only the same place as the ball while the ball happens to be centred:
 * Ballance's camera lags the ball, so the ball drifts by tens of pixels while rolling and the
 * crosshair stayed behind. {@link CameraSync#crosshairOffsetGui} now projects the aim point -- the
 * point the placement ray lands on, anchored to the ball -- through the camera, so the crosshair
 * follows the ball in both axes and still marks where a block would appear.
 *
 * <p>Done as a translation around the extraction of the crosshair only: the rest of the HUD (the
 * hotbar, the hand, any overlay) stays exactly where Minecraft puts it.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
    @Unique
    private boolean ballancePassthrough$crosshairLifted;

    @Inject(method = "extractCrosshair", at = @At("HEAD"))
    private void ballancePassthrough$liftCrosshair(GuiGraphicsExtractor graphics,
                                                   DeltaTracker deltaTracker,
                                                   CallbackInfo callback) {
        final Minecraft client = Minecraft.getInstance();
        if (client.getWindow() == null) {
            return;
        }
        final float[] offset = CameraSync.crosshairOffsetGui(
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight());
        if (offset[0] == 0.0f && offset[1] == 0.0f) {
            return;
        }
        graphics.pose().pushMatrix();
        // GUI pixels, y downwards: the offset already carries both signs.
        graphics.pose().translate(offset[0], offset[1]);
        this.ballancePassthrough$crosshairLifted = true;
    }

    @Inject(method = "extractCrosshair", at = @At("RETURN"))
    private void ballancePassthrough$dropCrosshair(GuiGraphicsExtractor graphics,
                                                   DeltaTracker deltaTracker,
                                                   CallbackInfo callback) {
        if (!this.ballancePassthrough$crosshairLifted) {
            return;
        }
        this.ballancePassthrough$crosshairLifted = false;
        graphics.pose().popMatrix();
    }
}
