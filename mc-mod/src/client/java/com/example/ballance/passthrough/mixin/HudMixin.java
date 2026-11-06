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
 * Draws the crosshair where the aim will actually put a block (milestone M1b).
 *
 * <p>The passthrough overlays the Minecraft frame on the Ballance one, and the ball is drawn by
 * Ballance at the centre of its frame. The crosshair is the centre of this frame, so the two met
 * exactly, and a block placed at the crosshair landed on the ball. The aim lift rotates the
 * player's rotation, which is what the placement ray follows; this moves the crosshair to the
 * angle that placement is seen from, so the crosshair and the block agree again.
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
        final float offset = CameraSync.crosshairOffsetPixels(client.getWindow().getGuiScaledHeight());
        if (offset == 0.0f) {
            return;
        }
        graphics.pose().pushMatrix();
        // Screen y grows downwards; a block placed above the camera axis has to be
        // aimed at with a crosshair that is above the centre.
        graphics.pose().translate(0.0f, -offset);
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
