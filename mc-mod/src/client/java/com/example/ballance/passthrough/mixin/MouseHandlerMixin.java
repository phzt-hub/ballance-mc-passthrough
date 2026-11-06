package com.example.ballance.passthrough.mixin;

import com.example.ballance.passthrough.MouseHandlerAccessor;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Writes the pointer position the Ballance window reports into the one Minecraft reads.
 *
 * <p>The Minecraft window never sees a pointer event in passthrough mode -- the Ballance window
 * owns the pointer -- so {@code MouseHandler}'s own position is whatever it was left at. The
 * inventory is laid out for this window's GUI space, and hovering a slot is decided from this
 * position, so it has to be set explicitly.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin implements MouseHandlerAccessor {
    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

    @Override
    public void ballancePassthrough$setPosition(double x, double y) {
        this.xpos = x;
        this.ypos = y;
    }
}
