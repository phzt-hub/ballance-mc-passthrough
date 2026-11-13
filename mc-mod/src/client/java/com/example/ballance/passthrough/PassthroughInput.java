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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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
        // A press is answered by the server a tick or two later, not by the press itself:
        // this is where that answer is read back (2026-11-08).
        checkPlacement(client);

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
        if (client.player == null || client.level == null || !pose.inLevel() || !pose.hasBall()) {
            // Nothing to act on while Ballance is still in its menus: the camera channel is
            // publishing from the first frame, but a click on START is not a click in the
            // world. FLAG_LEVEL is what says a level is running -- hasBall() cannot, because
            // the menu composition has ball-shaped objects of its own (2026-11-07: the menu
            // reported one at the origin, so every menu click was reaching the world and
            // marking the world as built in).
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
            if (down) {
                LevelSession.noteWorldTouched();
            }
            LOGGER.info("world left {} aim={} held={}", down ? "press" : "release",
                    describe(aimResult(client)), client.player.getMainHandItem());
        }
        if ((changed & McCameraChannel.BUTTON_RIGHT) != 0) {
            final boolean down = (buttons & McCameraChannel.BUTTON_RIGHT) != 0;
            seenButtons = down ? (seenButtons | McCameraChannel.BUTTON_RIGHT)
                    : (seenButtons & ~McCameraChannel.BUTTON_RIGHT);
            if (down) {
                // A world that has been clicked in is a world with blocks in it: the next
                // level entered from the menu gets a fresh one (LevelSession).
                LevelSession.noteWorldTouched();
                rightPress(client);
            } else {
                mouseKey(3, false);
            }
            if (!down) {
                LOGGER.info("world right release aim={} held={}",
                        describe(aimResult(client)), client.player.getMainHandItem());
            }
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

    // ------------------------------------------------------------ the world's right button

    /**
     * What a right press does, and what it says it did (2026-11-08).
     *
     * <p>There are four cases and only the first is vanilla. The one that mattered is the third:
     * the ray hits a block, but the cell the block would go into is taken, so vanilla has nothing
     * to do and the server silently declines -- no packet in the log, no block, no explanation.
     * When the block standing in that cell is the level's own barrier geometry (the invisible
     * collider that stands in for a Ballance surface, which is what most walls of a level are),
     * the surface under the crosshair <em>is</em> that cell, so the block is written there
     * instead. Every other refusal is printed with the cell's actual content: the user's "this
     * spot never takes a block, two steps along it always does" was this case, and it was the
     * silent one.
     */
    private static void rightPress(Minecraft client) {
        final HitResult aim = aimResult(client);
        final net.minecraft.world.item.ItemStack held = client.player.getMainHandItem();
        if (aim == null || aim.getType() != HitResult.Type.BLOCK) {
            // Nothing under the aim to place against (a world with nothing in it yet): vanilla
            // finds no block and falls through to useItem, where a plain BlockItem does nothing.
            // So the block is spawned at the end of the reach instead. Once one exists the ray
            // hits it and this stops being reached.
            if (aim != null) {
                client.hitResult = aim;
            }
            final boolean placed = placeAtAim(client);
            LOGGER.info("world right press aim={} held={} {} {}", describe(aim), held,
                    placed ? "placed at the end of the reach" : "nothing placed, using the item",
                    serverView(client));
            // Dispatched either way: a bow, a bucket or a pearl still does its own thing in the
            // air, and the line above has already said whether a block went down.
            mouseKey(3, true);
            return;
        }
        final BlockHitResult block = (BlockHitResult) aim;
        final BlockPos surface = block.getBlockPos();
        final BlockPos target = surface.relative(block.getDirection());
        // The placement packet is built from this field later in the tick, so it is refreshed
        // from the very pick that is about to be logged and acted on. A stale one is a placement
        // the server refuses and the client takes back: the block flashes and is gone.
        client.hitResult = aim;
        // The same test the server applies to the packet (BlockItem.place, canBeReplaced), so
        // that "the log says open" means "the server will take this one".
        if (client.level != null && client.level.getBlockState(target).canBeReplaced()) {
            LOGGER.info("world right press aim={} held={} target=open {}",
                    describe(aim), held, serverView(client));
            mouseKey(3, true);
            watchPlacement(client, target, "open");
            return;
        }
        if (client.level != null && client.level.getBlockState(surface).is(Blocks.BARRIER)
                && placeSurface(client, surface, held)) {
            LOGGER.info("world right press aim={} held={} target=barrier, replaced the surface itself {}",
                    describe(aim), held, serverView(client));
            watchPlacement(client, surface, "surface");
            return;
        }
        // No cell free and nothing that may be overwritten. Say so, with what is in the way.
        LOGGER.info("world right press aim={} held={} blocked_by={} -> refused {}",
                describe(aim), held,
                client.level == null ? "?" : client.level.getBlockState(target), serverView(client));
    }

    /**
     * Writes the held block into a cell the level's barrier is standing in, when that leaves the
     * level as solid as it was (2026-11-08).
     *
     * <p>The barrier is what the ball rolls on, so it is only replaced by something whose
     * collision shape is a full cube: a torch, a rail or a slab would take the floor out from
     * under the ball. Everything else is refused here, before anything is written.
     */
    private static boolean placeSurface(Minecraft client, BlockPos pos,
            net.minecraft.world.item.ItemStack held) {
        if (!(held.getItem() instanceof BlockItem item)) {
            return false;
        }
        final BlockState state = item.getBlock().defaultBlockState();
        if (!state.isCollisionShapeFullBlock(client.level, pos)) {
            LOGGER.info("surface: {} is not a full cube, the barrier stays", state.getBlock());
            return false;
        }
        final ServerLevel level = serverLevel(client);
        return level != null && level.setBlockAndUpdate(pos, state);
    }

    private static ServerLevel serverLevel(Minecraft client) {
        final IntegratedServer server = client.getSingleplayerServer();
        return server == null || client.level == null
                ? null : server.getLevel(client.level.dimension());
    }

    // ---------------------------------------------------------------- placement watch

    /** Ticks a press is given to be answered before the block is called missing (2026-11-08). */
    private static final int PLACEMENT_TICKS = 4;

    private static int pendingTicks;
    private static BlockPos pendingPos;
    private static Block pendingBlock;
    private static String pendingHow;

    /**
     * Remembers what a press should have put down, so the next few ticks can say whether it did
     * (2026-11-08).
     *
     * <p>The press cannot answer for itself: the block appears when the integrated server has
     * accepted the packet and the client has taken the update back, one or two ticks later. Until
     * now the log had the press and nothing else, so "the click went nowhere" and "the click
     * worked" were the same line with different outcomes, and every question about it had to be
     * argued from position readings.
     */
    private static void watchPlacement(Minecraft client, BlockPos pos, String how) {
        pendingPos = null;
        if (!(client.player.getMainHandItem().getItem() instanceof BlockItem item)) {
            return;
        }
        pendingPos = pos.immutable();
        pendingBlock = item.getBlock();
        pendingHow = how;
        pendingTicks = PLACEMENT_TICKS;
    }

    private static void checkPlacement(Minecraft client) {
        if (pendingPos == null || client.level == null) {
            return;
        }
        final BlockState now = client.level.getBlockState(pendingPos);
        if (pendingBlock != null && now.is(pendingBlock)) {
            LOGGER.info("placement ok ({}): {} at {} after {} ticks", pendingHow, pendingBlock,
                    pendingPos, PLACEMENT_TICKS - pendingTicks + 1);
            pendingPos = null;
            return;
        }
        if (--pendingTicks <= 0) {
            LOGGER.info("placement refused ({}): {} at {} is still {}", pendingHow, pendingBlock,
                    pendingPos, now);
            pendingPos = null;
        }
    }

    /**
     * The position and block state the <b>server</b> has, next to the client's (2026-11-08).
     *
     * <p>"That one spot never takes a block, anywhere else is fine" looks identical whether
     * the client is aiming at nothing or the server is refusing what it sent: both end with no
     * block. The server's player position and its own copy of the target block are what tells
     * the two apart, so every right press prints both.
     */
    private static String serverView(Minecraft client) {
        final IntegratedServer server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null) {
            return "srv=(none)";
        }
        final ServerLevel level = server.getLevel(client.level.dimension());
        if (level == null) {
            return "srv=(no level)";
        }
        final java.util.List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return "srv=(no player)";
        }
        final ServerPlayer player = players.get(0);
        final HitResult hit = client.hitResult;
        final BlockPos target = hit instanceof net.minecraft.world.phys.BlockHitResult block
                ? block.getBlockPos().relative(block.getDirection()) : null;
        final var serverEye = player.getEyePosition();
        final var clientEye = client.player.getEyePosition();
        return String.format("srvEye=(%.2f,%.2f,%.2f) cliEye=(%.2f,%.2f,%.2f) off=%.2f target=%s clientSays=%s serverSays=%s",
                serverEye.x, serverEye.y, serverEye.z,
                clientEye.x, clientEye.y, clientEye.z,
                serverEye.distanceTo(clientEye), target,
                target == null ? "-" : client.level.getBlockState(target),
                target == null ? "-" : level.getBlockState(target));
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
