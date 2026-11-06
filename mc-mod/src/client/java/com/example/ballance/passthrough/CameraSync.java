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

    /** How far a block placed at the end of the reach lands from the eye, in blocks. */
    private static final double REACH_BLOCKS = 4.5;

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
        final float[] yawPitch = aim(McCameraMapper.yawPitch(pose.forward()), pose.aimLift());
        LOGGER.info(String.format(
                "applied pose: eye=(%.2f, %.2f, %.2f) yaw=%.2f pitch=%.2f (lift %.2f) fov=%.2f aspect=%.3f ball=%s",
                eye[0], eye[1], eye[2], yawPitch[0], yawPitch[1], Math.toDegrees(pose.aimLift()),
                Math.toDegrees(pose.fovY()), pose.aspect(),
                ball == null ? "(none)" : String.format("(%.2f, %.2f, %.2f)", ball[0], ball[1], ball[2])));
    }

    public static McCameraChannel.Pose framePose() {
        return framePose;
    }

    /**
     * Where the crosshair has to be drawn, in GUI pixels above the centre, so that it sits on the
     * block the current aim would place.
     *
     * <p>This is what the panel's AimLift ends up as. The lift rotates the player's aim -- the
     * rotation Minecraft starts the placement ray from -- and a block placed at the end of the
     * reach is seen by the camera at some angle above its own axis. Drawing the crosshair there
     * is what makes "aim here, the block appears there" true. Tilting the camera instead moves
     * nothing relative to the ball: the ball is drawn by Ballance at the centre of its frame, and
     * the crosshair is the centre of this one.
     *
     * <p>Returns 0 when there is no pose yet, so the stock crosshair is left alone.
     */
    public static float crosshairOffsetPixels(int guiHeight) {
        final McCameraChannel.Pose pose = framePose;
        if (pose == null || !pose.valid() || guiHeight <= 0 || pose.fovY() <= 0.0f) {
            return 0.0f;
        }
        if (!McCameraMapper.ready()) {
            return 0.0f;
        }
        final Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0.0f;
        }
        final double[] eye = McCameraMapper.toBlocks(pose.eye());
        final float[] yawPitch = aim(McCameraMapper.yawPitch(pose.forward()), pose.aimLift());
        final double yaw = Math.toRadians(yawPitch[0]);
        final double pitch = Math.toRadians(yawPitch[1]);
        final double cosPitch = Math.cos(pitch);
        final double aimX = -Math.sin(yaw) * cosPitch;
        final double aimY = -Math.sin(pitch);
        final double aimZ = Math.cos(yaw) * cosPitch;

        // The block goes at the end of the reach, measured from the player's eye:
        // that is where Minecraft starts its placement ray.
        final double eyeX = client.player.getX();
        final double eyeY = client.player.getY() + client.player.getEyeHeight();
        final double eyeZ = client.player.getZ();
        final double blockX = eyeX + aimX * REACH_BLOCKS;
        final double blockY = eyeY + aimY * REACH_BLOCKS;
        final double blockZ = eyeZ + aimZ * REACH_BLOCKS;

        // ... and the camera sees that point at an angle off its own axis.
        final double[] forward = {pose.forward()[0], pose.forward()[1], -pose.forward()[2]};
        final double[] up = {pose.up()[0], pose.up()[1], -pose.up()[2]};
        final double vx = blockX - eye[0];
        final double vy = blockY - eye[1];
        final double vz = blockZ - eye[2];
        final double along = vx * forward[0] + vy * forward[1] + vz * forward[2];
        final double above = vx * up[0] + vy * up[1] + vz * up[2];
        if (along <= 1.0e-6) {
            return 0.0f;
        }
        final double angle = Math.atan2(above, along);
        final double halfFov = pose.fovY() * 0.5;
        return (float) (guiHeight * 0.5 * Math.tan(angle) / Math.tan(halfFov));
    }

    /**
     * Ballance's view direction as Minecraft yaw/pitch, tilted up by the panel's aim lift.
     *
     * <p>Why a lift exists at all: the passthrough draws the Minecraft frame over the Ballance
     * one, so the camera that was placed on the ball put the crosshair exactly on it, and a
     * block placed at the crosshair landed on the ball. Tilting the Minecraft camera up (not
     * the Ballance one -- that one keeps the viewpoint the Ballance frame was rendered from)
     * moves the crosshair clear of the ball's silhouette. Minecraft's pitch is positive
     * downwards, so lifting means subtracting.
     */
    public static float[] aim(float[] yawPitch, float liftRadians) {
        if (liftRadians == 0.0f) {
            return yawPitch;
        }
        final float lift = (float) Math.toDegrees(liftRadians);
        final float pitch = yawPitch[1] - lift;
        return new float[]{yawPitch[0], Math.max(-90.0f, Math.min(90.0f, pitch))};
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
        // The same angles the camera is installed with, aim lift included: the
        // block interaction ray starts at the player's eye along its rotation, so
        // a lift the camera took and the player did not would put the block
        // somewhere other than the crosshair.
        final float[] yawPitch = aim(McCameraMapper.yawPitch(pose.forward()), pose.aimLift());
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
