package com.example.ballance.passthrough;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a world on startup, for unattended runs: {@code BALLANCE_MC_WORLD=<level id>}.
 *
 * <p>Minecraft's own {@code --quickPlaySingleplayer} never reached the game: loom writes
 * program arguments into an arg file and Gradle's {@code --args} is dropped (measured
 * 2026-11-05 -- the game logged "Completely ignored arguments"). Driving the title screen with
 * synthetic clicks is unreliable on a bare X server too (no window manager, no focus), so the
 * hook goes through the same {@code WorldOpenFlows} the quick-play path uses.
 *
 * <p>Fires once, a few seconds after startup, and only while no world is loaded. This build has
 * no public "current screen" accessor to gate on, so the delay does the gating: by then the
 * title screen is up and its resources are loaded.
 */
public final class McWorldAutoload {
    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-world");
    private static final String WORLD = System.getenv("BALLANCE_MC_WORLD");
    private static final long DELAY_NANOS = 6_000_000_000L;

    private static final long STARTED = System.nanoTime();
    private static boolean attempted;

    private McWorldAutoload() {
    }

    public static void onClientTick(Minecraft client) {
        if (WORLD == null || WORLD.isBlank() || attempted) {
            return;
        }
        if (client.level != null) {
            attempted = true;
            return;
        }
        if (System.nanoTime() - STARTED < DELAY_NANOS) {
            return;
        }
        attempted = true;
        LOGGER.info("opening world \"{}\" (BALLANCE_MC_WORLD)", WORLD);
        client.createWorldOpenFlows().openWorld(WORLD,
                () -> LOGGER.warn("world \"{}\" could not be opened", WORLD));
    }
}
