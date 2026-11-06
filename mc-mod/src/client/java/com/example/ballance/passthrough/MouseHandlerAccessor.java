package com.example.ballance.passthrough;

/**
 * Lets the passthrough move the window pointer without a real pointer move.
 *
 * <p>Hover highlighting and the item tooltip are drawn from the position the window reports,
 * not from the coordinates handed to the click handlers, so the same position has to be written
 * where {@code GameRenderer} reads it. Implemented by {@code MouseHandlerMixin}.
 */
public interface MouseHandlerAccessor {
    void ballancePassthrough$setPosition(double x, double y);
}
