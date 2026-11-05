package com.example.ballance.passthrough;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the Ballance camera channel to the Minecraft client (milestone M1).
 *
 * <p>Two jobs, both driven from the channel described by {@link McCameraChannel}:
 * <ul>
 *   <li>the camera itself, installed by {@code CameraMixin} at the end of
 *       {@code Camera.alignWithEntity} -- that is after Minecraft has placed its own camera from
 *       the player, and before the cull frustum and the projection are built, so everything
 *       downstream (culling, render state, HUD, projection) agrees with it;</li>
 *   <li>the player entity, moved onto the ball once per client tick. The camera does not need
 *       it (the mixin overrides the camera outright), but chunks are loaded around the player,
 *       and a camera 40 blocks from the player sees void.</li>
 * </ul>
 *
 * <p>Off with BALLANCE_MC_CAMERA=0. When Ballance is not publishing, nothing above runs: the
 * channel maps to nothing and the client behaves as if the mod were not installed.
 */
public final class CameraSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-camera");

    /** Largest per-tick move the client may ask the server to accept, in blocks. */
    private static final double MAX_STEP = 8.0;

    private static final boolean ENABLED = !"0".equals(System.getenv("BALLANCE_MC_CAMERA"));

    private static volatile McCameraChannel.Pose framePose;
    private static boolean announced;
    private static int applied;
    private static long lastLogNanos;

    private CameraSync() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Samples the channel once per rendered frame. Called from the camera mixin, so the pose a
     * frame renders with and the pose its FOV is derived from are the same sample.
     */
    public static void refreshForFrame() {
        if (!ENABLED) {
            return;
        }
        framePose = McCameraChannel.read();
        final McCameraChannel.Pose pose = framePose;
        if (pose == null || !pose.valid()) {
            return;
        }
        // One line every two seconds, on the render thread: the values the camera is
        // actually being driven with, already converted to blocks. The Ballance side logs
        // the same pose in units, so "the two halves see the same thing" is a diff, not
        // an opinion.
        final long now = System.nanoTime();
        if (now - lastLogNanos < 2_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        McCameraMapper.ensure(pose);
        final double[] eye = McCameraMapper.toBlocks(pose.eye());
        final double[] ball = pose.hasBall() ? McCameraMapper.toBlocks(pose.ball()) : null;
        final float[] yawPitch = McCameraMapper.yawPitch(pose.forward());
        LOGGER.info(String.format(
                "applied pose: eye=(%.2f, %.2f, %.2f) yaw=%.2f pitch=%.2f fov=%.2f aspect=%.3f ball=%s",
                eye[0], eye[1], eye[2], yawPitch[0], yawPitch[1],
                Math.toDegrees(pose.fovY()), pose.aspect(),
                ball == null ? "(none)" : String.format("(%.2f, %.2f, %.2f)", ball[0], ball[1], ball[2])));
    }

    public static McCameraChannel.Pose framePose() {
        return framePose;
    }

    public static void onClientTick(Minecraft client) {
        if (!ENABLED) {
            return;
        }
        final McCameraChannel.Pose pose = McCameraChannel.read();
        if (pose == null || !pose.valid() || !pose.hasBall()) {
            return;
        }
        final LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }

        McCameraMapper.ensure(pose);
        final double[] target = McCameraMapper.toBlocks(pose.ball());
        double x = target[0];
        double y = target[1];
        double z = target[2];

        final double dx = x - player.getX();
        final double dy = y - player.getY();
        final double dz = z - player.getZ();
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > MAX_STEP) {
            // Vanilla's server rejects a move of more than ~10 blocks per tick ("moved too
            // quickly") and teleports the player back; creeping after the ball is better than
            // fighting the correction.
            final double scale = MAX_STEP / distance;
            x = player.getX() + dx * scale;
            y = player.getY() + dy * scale;
            z = player.getZ() + dz * scale;
        }

        player.snapTo(x, y, z);
        final float[] yawPitch = McCameraMapper.yawPitch(pose.forward());
        player.setYRot(yawPitch[0]);
        player.setXRot(yawPitch[1]);
        player.setOldPosAndRot();

        if (!announced) {
            announced = true;
            LOGGER.info("camera sync running ({}); ball at {},{} {}",
                    McCameraMapper.describe(), (int) x, (int) y, (int) z,
                    McCameraChannel.label());
        }
        if ((++applied % 200) == 0) {
            LOGGER.info("camera sync: {} samples, player at {} {} {}",
                    applied, (int) x, (int) y, (int) z);
        }
    }
}
