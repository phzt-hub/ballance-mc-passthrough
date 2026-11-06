package com.example.ballance.passthrough;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client entry point: installs the Minecraft -&gt; Ballance frame export (M0b).
 *
 * <p>The mixin in {@link com.example.ballance.passthrough.mixin.MinecraftFrameMixin} calls
 * {@link McFrameExport#onFrameRendered} at the tail of every rendered frame; this class
 * only reads the configuration and creates the mailbox header early so that the Ballance
 * consumer can map it before the first frame is published.
 */
public final class PassthroughClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        McFrameExport.initialise();
        // The "do not pause when another window has the focus" policy is applied
        // on the first client tick instead: this entrypoint runs inside
        // Minecraft's constructor, before Options exists.
        // M1: the camera channel is opened lazily on the first frame, because the
        // Ballance side creates the shared object when it starts publishing and
        // either process may come up first.
        if (!CameraSync.enabled()) {
            LOGGER.info("camera sync disabled by BALLANCE_MC_CAMERA=0");
        }
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("ballance-passthrough-camera");
}
