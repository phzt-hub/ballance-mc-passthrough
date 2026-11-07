package com.example.ballance.passthrough;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Minecraft world per Ballance level (2026-11-07).
 *
 * <p>Every level used to be played in the same save, so a bridge built in level 1 was still
 * standing when level 8 started (all levels share one anchor, so it stood right where the next
 * level began). The user asked for a clean world each time a level is entered from the menu, and
 * chose a real delete-and-recreate over clearing the world in place.
 *
 * <p>The signal is the camera channel: since 2026-11-07 it carries {@code MPC_FLAG_LEVEL}, which
 * the Ballance side sets while the attached camera is a gameplay one. That flag is what tells a
 * level from a menu -- the menu composition has ball-shaped objects of its own, parked at the
 * origin, so "a ball exists" is not "a level is running" (measured: the menu reported a ball at
 * (0,0,0) with FLAG_BALL set). A ball respawning after a death never clears the flag, so a level
 * is only re-entered from the menu, which is what the user asked for.
 *
 * <p>A world that has not been played in is kept: starting Minecraft while Ballance sits in a
 * menu already gives a brand-new world, and replacing it on the first level start would be a
 * loading screen for nothing. Only a world a world-click has gone into is replaced.
 *
 * <p>While the client has no world it renders its loading screen, and the frame export would put
 * that on top of the Ballance view; so one blank (fully transparent) frame is published first and
 * the export is suspended until the new world is up. The composite then shows Ballance's own
 * level for the whole reload.
 *
 * <p>Off with {@code BALLANCE_MC_NEWWORLD=0}.
 */
public final class LevelSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-level");

    private static final boolean ENABLED = !"0".equals(System.getenv("BALLANCE_MC_NEWWORLD"));

    /** How long the delete-and-recreate may take before it is given up on. */
    private static final long TIMEOUT_NANOS = 30_000_000_000L;

    /**
     * Client ticks the level flag has to stay up before it counts as a level start (0.3 s at
     * 20 tps), and how long it has to stay down before "outside a level" is believed again.
     * The Ballance side derives the flag from the attached camera, and a level's first frames
     * pass through the menu camera, so without the confirmation a start would fire early.
     */
    private static final int LEVEL_CONFIRM_TICKS = 6;
    private static final int MENU_CONFIRM_TICKS = 10;

    private enum State {
        IDLE,
        /** The old world has been dropped; the next world has not been created yet. */
        LEAVING,
        /** A world is being created/loaded; waiting for {@code client.level} to appear. */
        WAITING,
    }

    private static State state = State.IDLE;
    private static long stateSince;

    private static boolean sampled;
    private static boolean outsideLevel = true;
    private static int levelTicks;
    private static int menuTicks;
    private static boolean disconnectPending;

    /** The world the passthrough is playing in has had a world click: blocks may exist. */
    private static boolean touched;

    /** Save id of the world being replaced, deleted once its replacement is up. */
    private static String previousWorld;

    private LevelSession() {
    }

    public static void onClientTick(Minecraft client) {
        if (state != State.IDLE) {
            tickRecreate(client);
            return;
        }
        if (!ENABLED) {
            return;
        }
        observe(client);
    }

    /** Called by {@link PassthroughInput} for every world click it forwards. */
    public static void noteWorldTouched() {
        touched = true;
    }

    private static void observe(Minecraft client) {
        final McCameraChannel.Pose pose = McCameraChannel.read();
        final boolean inLevel = pose != null && pose.valid() && pose.inLevel();
        final boolean hasBall = inLevel && pose.hasBall();
        if (inLevel) {
            ++levelTicks;
            menuTicks = 0;
        } else {
            ++menuTicks;
            levelTicks = 0;
        }
        if (!sampled) {
            // First readable sample: whatever it says is the baseline, not an edge. This is the
            // client starting up in the middle of a level.
            sampled = true;
            outsideLevel = !inLevel;
            if (hasBall) {
                McCameraMapper.reanchor(pose);
            }
            return;
        }
        if (outsideLevel && hasBall && levelTicks >= LEVEL_CONFIRM_TICKS) {
            outsideLevel = false;
            onLevelEntered(client, pose);
            return;
        }
        if (menuTicks >= MENU_CONFIRM_TICKS) {
            outsideLevel = true;
        }
    }

    private static void onLevelEntered(Minecraft client, McCameraChannel.Pose pose) {
        McCameraMapper.reanchor(pose);
        LOGGER.info("level start: ball at {} -> mapping origin {} (the ball starts at the anchor)",
                String.format("(%.1f, %.1f, %.1f)", pose.ball()[0], pose.ball()[1], pose.ball()[2]),
                McCameraMapper.origin());
        if (!McWorldBootstrap.isPassthroughWorld()) {
            touched = false;
            return;
        }
        if (!touched || client.level == null) {
            LOGGER.info("level start: this world has not been built in, keeping it");
            return;
        }
        beginRecreate(client);
    }

    private static void beginRecreate(Minecraft client) {
        previousWorld = McWorldBootstrap.currentWorldId();
        state = State.LEAVING;
        stateSince = System.nanoTime();
        disconnectPending = true;
        LOGGER.info("level start: replacing the world (previous save \"{}\")", previousWorld);
        // Blank first: the last thing the Ballance window should show of this world is nothing.
        McFrameExport.publishBlank();
        McFrameExport.setSuspended(true);
    }

    private static void tickRecreate(Minecraft client) {
        if (System.nanoTime() - stateSince > TIMEOUT_NANOS) {
            LOGGER.warn("world replacement timed out in {}; leaving the save alone", state);
            finish(client, false);
            return;
        }
        switch (state) {
            case LEAVING -> {
                if (disconnectPending) {
                    // On the next tick rather than inside the tick that detected the level: the
                    // world has just finished ticking, and this is the way out of it.
                    disconnectPending = false;
                    client.disconnect(new TitleScreen(), false);
                    return;
                }
                if (client.level != null) {
                    return;
                }
                state = State.WAITING;
                stateSince = System.nanoTime();
                try {
                    McWorldBootstrap.createFreshWorld(client);
                } catch (Throwable throwable) {
                    LOGGER.error("could not create the next world", throwable);
                    finish(client, false);
                }
            }
            case WAITING -> {
                if (client.level == null) {
                    return;
                }
                finish(client, true);
            }
            default -> {
            }
        }
    }

    private static void finish(Minecraft client, boolean ready) {
        state = State.IDLE;
        McFrameExport.setSuspended(false);
        touched = false;
        // The ball of the level being entered is already on screen; make the next sample a
        // baseline instead of the edge that would replace this world again.
        sampled = false;
        outsideLevel = false;
        levelTicks = 0;
        menuTicks = 0;
        if (ready) {
            LOGGER.info("world ready: \"{}\" (previous save {})", McWorldBootstrap.currentWorldId(),
                    previousWorld == null ? "(none)" : "\"" + previousWorld + "\"");
            if (previousWorld != null) {
                McWorldBootstrap.deleteWorld(client, previousWorld);
            }
        }
        previousWorld = null;
    }
}
