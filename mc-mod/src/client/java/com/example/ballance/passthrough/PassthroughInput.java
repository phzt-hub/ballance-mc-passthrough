package com.example.ballance.passthrough;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the Minecraft inventory from the Ballance window (milestone M1b).
 *
 * <p>In passthrough mode the Ballance window owns the keyboard and the pointer, and the
 * Minecraft window is behind it: Minecraft receives no key events at all. So the Ballance side
 * publishes what the pointer and two keys (E and Escape) are doing through the camera channel,
 * and this class turns that back into Minecraft input:
 *
 * <ul>
 *   <li>E opens the creative inventory and closes it again. Opening goes through
 *       {@link KeyMapping#click}, i.e. the same path a real E press takes, so Minecraft's own
 *       code builds the screen (tabs, the operator tab, the last tab remembered);</li>
 *   <li>while the screen is up, the pointer position, both mouse buttons and the wheel are fed
 *       to it, so items can be picked, dragged, scrolled and the tabs switched;</li>
 *   <li>the fact that the screen is up is written back to Ballance, which freezes the level for
 *       as long as it is -- a rolling ball would be gone before the block is picked.</li>
 * </ul>
 *
 * <p>Off with BALLANCE_MC_INPUT=0.
 */
public final class PassthroughInput {
    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-input");

    private static final boolean ENABLED = !"0".equals(System.getenv("BALLANCE_MC_INPUT"));

    /**
     * Mouse button codes as Minecraft's input layer numbers them in 26.3: straight from SDL's
     * {@code SDL_MouseButtonEvent.button}, where 1 is the left button, 2 the middle and 3 the
     * right one. (Before the SDL move these were GLFW's 0/1/2.)
     *
     * <p>Getting this wrong is silent rather than loud: {@code AbstractContainerScreen}
     * only treats 1 and 3 as a container click and hands everything else to the hotbar-swap
     * check, so a left click coded 0 did nothing at all and still reported "handled".
     */
    private static final MouseButtonInfo LEFT = new MouseButtonInfo(1, 0);
    private static final MouseButtonInfo RIGHT = new MouseButtonInfo(3, 0);

    /** The counters and buttons the client has already acted on. */
    private static int seenKeyE;
    private static int seenKeyEsc;
    private static int seenWheel;
    private static int seenButtons;
    private static double lastMouseX = Double.NaN;
    private static double lastMouseY = Double.NaN;
    private static int ticks;
    private static boolean focusPolicyApplied;
    private static net.minecraft.world.item.ItemStack lastCarried =
            net.minecraft.world.item.ItemStack.EMPTY;
    /** When the channel last moved, so a dead Ballance does not leave a key held down. */
    private static int lastPoseSeq;
    private static long lastPoseNanos;
    private static final long STALE_NANOS = 2_000_000_000L;

    private PassthroughInput() {
    }

    public static void onClientTick(Minecraft client) {
        if (!ENABLED) {
            return;
        }
        // Passthrough means the Ballance window is the one with focus, so
        // Minecraft is permanently "unfocused" and vanilla would drop to the
        // pause screen on the first tick -- which looks exactly like a frozen
        // frame, and would swallow the E key. Done here, not at mod init: the
        // client entrypoint runs inside Minecraft's constructor, before options
        // exist. In memory only; the player's options.txt is theirs.
        if (!focusPolicyApplied && client.options != null) {
            focusPolicyApplied = true;
            if (client.options.pauseOnLostFocus) {
                client.options.pauseOnLostFocus = false;
                LOGGER.info("pauseOnLostFocus off: the Ballance window owns the focus");
            }
        }
        final McCameraChannel.Pose pose = McCameraChannel.read();
        if (pose == null) {
            releaseButtons();
            return;
        }
        if (pose.seq() != lastPoseSeq) {
            lastPoseSeq = pose.seq();
            lastPoseNanos = System.nanoTime();
        } else if (System.nanoTime() - lastPoseNanos > STALE_NANOS) {
            // Ballance stopped publishing (it exited or was killed). A key it was holding
            // would otherwise stay held for ever: MouseHandler's own state is not reset when
            // the window it belongs to goes away.
            releaseButtons();
            return;
        }
        ++ticks;

        final Screen current = client.gui.screen();
        final boolean open = current instanceof CreativeModeInventoryScreen;
        handleKeys(client, pose, open);
        final boolean openNow = client.gui.screen() instanceof CreativeModeInventoryScreen;
        if (openNow) {
            forwardPointer(client, pose);
            // The carried stack only changes after the server answers a click, so
            // the press itself cannot report it; watch it instead.
            if (client.player != null) {
                final var carried = carriedOf(client, client.gui.screen());
                // ItemStack is not a value type: the menu hands out a fresh instance for
                // every read, so equals() only ever matches the shared EMPTY stack.
                if (!net.minecraft.world.item.ItemStack.matches(carried, lastCarried)) {
                    lastCarried = carried.copy();
                    LOGGER.info("carried is now {} hotbar={}", carried, hotbarSummary(client));
                }
            }
        } else {
            forwardWorldInput(client, pose);
            lastMouseX = Double.NaN;
            lastMouseY = Double.NaN;
        }

        // Ballance needs one bit of this back: is the inventory still on screen? That
        // is what freezes the level and hands the pointer over while it is.
        final Screen shown = client.gui.screen();
        McCameraChannel.writeStatus(openNow, shown == null ? 0 : shown.width,
                shown == null ? 0 : shown.height, seenKeyE, seenKeyEsc, ticks);
    }

    private static void handleKeys(Minecraft client, McCameraChannel.Pose pose, boolean open) {
        if (pose.keyE() != seenKeyE) {
            // A restarted Ballance starts its counters at 0 again; that is not a press.
            final boolean fresh = pose.keyE() < seenKeyE;
            seenKeyE = pose.keyE();
            if (!fresh) {
                toggleInventory(client, open);
            }
        }
        if (pose.keyEsc() != seenKeyEsc) {
            final boolean fresh = pose.keyEsc() < seenKeyEsc;
            seenKeyEsc = pose.keyEsc();
            final Screen shown = client.gui.screen();
            if (!fresh && shown instanceof CreativeModeInventoryScreen) {
                LOGGER.info("closing the creative inventory (Escape from Ballance)");
                shown.onClose();
            }
        }
    }

    private static void toggleInventory(Minecraft client, boolean open) {
        final Screen shown = client.gui.screen();
        if (open) {
            LOGGER.info("closing the creative inventory (E from Ballance)");
            shown.onClose();
            return;
        }
        if (shown != null) {
            // Something else owns the pointer (the pause screen after a lost focus,
            // for instance); opening underneath it would stack two screens.
            LOGGER.info("E ignored: another screen is open ({})", shown.getClass().getSimpleName());
            return;
        }
        if (client.player == null || client.level == null) {
            return;
        }
        LOGGER.info("opening the creative inventory (E from Ballance)");
        // The vanilla key path: Minecraft handles it in its own tick, with the
        // feature flags and operator tab the player's session actually has.
        KeyMapping.click(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_E));
    }

    private static void forwardPointer(Minecraft client, McCameraChannel.Pose pose) {
        final Screen screen = client.gui.screen();
        if (screen == null || screen.width <= 0 || screen.height <= 0) {
            return;
        }
        final double u = clamp01(pose.mouseX());
        final double v = clamp01(pose.mouseY());
        final double guiX = u * screen.width;
        final double guiY = v * screen.height;

        // The renderer reads the window's pointer position to decide which slot is
        // under the cursor, so it has to be moved too -- not just the event coords.
        final var window = client.getWindow();
        if (client.mouseHandler instanceof MouseHandlerAccessor accessor) {
            accessor.ballancePassthrough$setPosition(u * window.getWidth(), v * window.getHeight());
        }

        final double dx = Double.isNaN(lastMouseX) ? 0.0 : guiX - lastMouseX;
        final double dy = Double.isNaN(lastMouseY) ? 0.0 : guiY - lastMouseY;
        lastMouseX = guiX;
        lastMouseY = guiY;

        final int buttons = pose.buttons();
        final int changed = buttons ^ seenButtons;

        // Both buttons go through MouseHandler.onButton rather than straight into the
        // screen: that is the path a real press takes, so the screen also gets
        // afterMouseAction() (the double-click bookkeeping) and the release is routed
        // back as a release -- which is where a creative tab actually switches tabs.
        if ((changed & McCameraChannel.BUTTON_LEFT) != 0) {
            final boolean down = (buttons & McCameraChannel.BUTTON_LEFT) != 0;
            seenButtons = down ? (seenButtons | McCameraChannel.BUTTON_LEFT)
                    : (seenButtons & ~McCameraChannel.BUTTON_LEFT);
            client.mouseHandler.onButton(window.handle(), LEFT, down ? 1 : 0);
            // The carried stack is the only unambiguous answer to "did that click
            // pick something up": the hover highlight and the tooltip move for a
            // plain pointer move too. The press has already run by this line, so a
            // pick shows up here and the watcher in onClientTick catches anything
            // that only lands a tick later.
            LOGGER.info("left {} gui=({},{}) screen={}x{} win={}x{} scale={} rendererMouse=({},{}) carried={}",
                    down ? "press" : "release",
                    guiX, guiY, screen.width, screen.height,
                    window.getWidth(), window.getHeight(), window.getGuiScale(),
                    client.mouseHandler.getScaledXPos(window),
                    client.mouseHandler.getScaledYPos(window),
                    carriedOf(client, screen));
        } else if ((buttons & McCameraChannel.BUTTON_LEFT) != 0 && (dx != 0.0 || dy != 0.0)) {
            screen.mouseDragged(new MouseButtonEvent(guiX, guiY, LEFT), dx, dy);
        }

        // Right button: split a stack, and the second half of "put one down".
        if ((changed & McCameraChannel.BUTTON_RIGHT) != 0) {
            final boolean down = (buttons & McCameraChannel.BUTTON_RIGHT) != 0;
            seenButtons = down ? (seenButtons | McCameraChannel.BUTTON_RIGHT)
                    : (seenButtons & ~McCameraChannel.BUTTON_RIGHT);
            client.mouseHandler.onButton(window.handle(), RIGHT, down ? 1 : 0);
            LOGGER.info("right {} carried={}", down ? "press" : "release", carriedOf(client, screen));
        }

        // Wheel: the Ballance side counts Windows units (120 a notch) and publishes
        // the running total, so a notch that arrived between two client ticks is
        // still counted once.
        final int wheel = pose.wheel();
        if (wheel != seenWheel) {
            final int delta = wheel - seenWheel;
            seenWheel = wheel;
            if (delta > -1200 && delta < 1200) {
                // Also the vanilla path: it applies the player's wheel sensitivity and
                // turns the notches into the scroll the container actually uses.
                client.mouseHandler.onScroll(window.handle(), 0.0,
                        delta / McCameraChannel.WHEEL_UNITS_PER_NOTCH);
            }
        }
    }

    /**
     * The world half of the pointer: the same buttons, with no screen to receive them.
     *
     * <p>Deliberately not {@code MouseHandler.onButton}. That method's first move for a press
     * with no screen open is {@code grabMouse()}, and the pointer belongs to the Ballance window
     * here -- there is nothing for Minecraft to grab. What onButton does after that is three
     * lines, and those are the three lines used here: set the mouse key down, hand it one click.
     * {@code Minecraft.handleKeybinds} turns that into {@code startAttack} / {@code startUseItem}
     * on its next pass, exactly as it would for a real press.
     */
    private static void forwardWorldInput(Minecraft client, McCameraChannel.Pose pose) {
        if (client.player == null || client.level == null || !pose.hasBall()) {
            // Nothing to act on while Ballance is still in its menus: the camera channel is
            // publishing from the first frame, but a click on START is not a click in the
            // world. hasBall() is what says a level is running.
            seenButtons = pose.buttons();
            seenWheel = pose.wheel();
            return;
        }
        final int buttons = pose.buttons();
        final int changed = buttons ^ seenButtons;

        if ((changed & McCameraChannel.BUTTON_LEFT) != 0) {
            final boolean down = (buttons & McCameraChannel.BUTTON_LEFT) != 0;
            seenButtons = down ? (seenButtons | McCameraChannel.BUTTON_LEFT)
                    : (seenButtons & ~McCameraChannel.BUTTON_LEFT);
            mouseKey(1, down);
            LOGGER.info("world left {} aim={} held={}", down ? "press" : "release",
                    describe(aimResult(client)), client.player.getMainHandItem());
        }
        if ((changed & McCameraChannel.BUTTON_RIGHT) != 0) {
            final boolean down = (buttons & McCameraChannel.BUTTON_RIGHT) != 0;
            seenButtons = down ? (seenButtons | McCameraChannel.BUTTON_RIGHT)
                    : (seenButtons & ~McCameraChannel.BUTTON_RIGHT);
            if (down) {
                // A void world has nothing to place against: vanilla finds no block under the
                // aim and falls through to useItem, where a plain BlockItem does nothing. So the
                // block is spawned at the end of the reach instead. Once one exists the ray hits
                // it and the vanilla branch takes over, with no special case left behind.
                if (aimingAtBlock(client) || !placeAtAim(client)) {
                    mouseKey(3, true);
                }
            } else {
                mouseKey(3, false);
            }
            LOGGER.info("world right {} aim={} held={}", down ? "press" : "release",
                    describe(aimResult(client)), client.player.getMainHandItem());
        }
        if ((changed & McCameraChannel.BUTTON_MIDDLE) != 0) {
            final boolean down = (buttons & McCameraChannel.BUTTON_MIDDLE) != 0;
            seenButtons = down ? (seenButtons | McCameraChannel.BUTTON_MIDDLE)
                    : (seenButtons & ~McCameraChannel.BUTTON_MIDDLE);
            // Pick block. Nothing to prove beyond it arriving; the log of the held stack after
            // the middle click does that.
            mouseKey(2, down);
        }

        // With no screen open the wheel is the hotbar (the other branch of
        // MouseHandler.onScroll): that is also how the slot holding the block gets selected
        // while playing, without opening the inventory.
        final int wheel = pose.wheel();
        if (wheel != seenWheel) {
            final int delta = wheel - seenWheel;
            seenWheel = wheel;
            if (delta > -1200 && delta < 1200) {
                client.mouseHandler.onScroll(client.getWindow().handle(), 0.0,
                        delta / McCameraChannel.WHEEL_UNITS_PER_NOTCH);
            }
        }
    }

    /** Presses a mouse key the way {@code MouseHandler.onButton} leaves it for a world click. */
    private static void mouseKey(int button, boolean down) {
        final InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(button);
        KeyMapping.set(key, down);
        if (down) {
            KeyMapping.click(key);
        }
    }

    /** Lets go of everything, for when the Ballance side stops publishing. */
    private static void releaseButtons() {
        if (seenButtons == 0) {
            return;
        }
        seenButtons = 0;
        mouseKey(1, false);
        mouseKey(2, false);
        mouseKey(3, false);
    }

    /**
     * The block interaction ray, from the player's eye along the aim Minecraft itself uses --
     * the Ballance view direction tilted by the panel's aim lift (see {@code CameraSync.aim}),
     * which is the rotation the crosshair is drawn for.
     */
    private static HitResult aimResult(Minecraft client) {
        if (client.player == null) {
            return null;
        }
        return client.player.pick(CameraSync.REACH_BLOCKS, 0.0f, false);
    }

    private static boolean aimingAtBlock(Minecraft client) {
        final HitResult hit = aimResult(client);
        return hit != null && hit.getType() == HitResult.Type.BLOCK;
    }

    private static String describe(HitResult hit) {
        if (hit == null) {
            return "none";
        }
        if (hit.getType() == HitResult.Type.BLOCK
                && hit instanceof net.minecraft.world.phys.BlockHitResult block) {
            return block.getBlockPos() + "/" + block.getDirection();
        }
        return hit.getType().toString();
    }

    /**
     * Puts the held block at the end of the reach when there is nothing under the aim.
     *
     * <p>This is the one non-vanilla branch of the feature. It spawns the block where the
     * crosshair is drawn for -- {@code CameraSync.crosshairOffsetPixels} measures that
     * placement with the same reach -- so "aim here, it appears there" stays true even with
     * no block to place against.
     *
     * <p>Set on the integrated server, not the client: a client-side setBlock never reaches
     * the server and the block is erased by the next chunk update.
     *
     * @return whether a block was placed
     */
    private static boolean placeAtAim(Minecraft client) {
        if (client.player == null || client.level == null) {
            return false;
        }
        if (!(client.player.getMainHandItem().getItem() instanceof BlockItem item)) {
            return false;
        }
        final var eye = client.player.getEyePosition();
        final var target = eye.add(client.player.getLookAngle().scale(CameraSync.REACH_BLOCKS));
        final BlockPos pos = BlockPos.containing(target);
        final IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        final ServerLevel level = server.getLevel(client.level.dimension());
        if (level == null) {
            return false;
        }
        final var state = item.getBlock().defaultBlockState();
        if (!level.setBlockAndUpdate(pos, state)) {
            return false;
        }
        LOGGER.info("placed {} at {} eye=({},{},{}) target=({},{},{})",
                state.getBlock(), pos,
                String.format("%.2f", eye.x), String.format("%.2f", eye.y),
                String.format("%.2f", eye.z),
                String.format("%.2f", target.x), String.format("%.2f", target.y),
                String.format("%.2f", target.z));
        return true;
    }

    /**
     * The stack on the cursor, read from the menu the screen is actually driving. In the
     * creative inventory that is the item picker menu rather than the player's own, and
     * the server is never asked for it: the creative cursor is client state.
     */
    private static net.minecraft.world.item.ItemStack carriedOf(Minecraft client, Screen screen) {
        if (client.player == null) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container) {
            return container.getMenu().getCarried();
        }
        return client.player.containerMenu.getCarried();
    }

    /** The nine hotbar stacks, so a click that moves an item can be checked from the log. */
    private static String hotbarSummary(Minecraft client) {
        if (client.player == null) {
            return "?";
        }
        final var inventory = client.player.getInventory();
        final StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) {
                text.append(", ");
            }
            final var stack = inventory.getItem(i);
            text.append(stack.isEmpty() ? "-" : stack.getCount() + " " + stack.getItem());
        }
        return text.append(']').toString();
    }

    private static double clamp01(float value) {
        return value < 0.0f ? 0.0 : (value > 1.0f ? 1.0 : value);
    }
}
