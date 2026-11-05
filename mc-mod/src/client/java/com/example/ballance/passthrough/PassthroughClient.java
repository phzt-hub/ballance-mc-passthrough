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
    }
}
