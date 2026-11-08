package com.example.ballance.passthrough;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    /**
     * How far a block placed at the end of the reach lands from the eye, in blocks. Shared with
     * {@code PassthroughInput}, which spawns the first block of a void world at exactly this
     * distance: the crosshair is drawn for this placement, so the two must not drift apart.
     */
    static final double REACH_BLOCKS = 4.5;

    /**
     * How far above the ball the crosshair sits, in GUI pixels, per degree of the panel's aim
     * lift (2026-11-08).
     *
     * <p>The panel row is still in degrees and is still the only control: 20 degrees -- the
     * default -- is 66 GUI px, which is where the fixed-angle lift had been putting the crosshair
     * on the 7.5-block camera the 2026-11-07 measurement was taken on. What changed is that the
     * number is a distance on the screen now instead of an angle of rotation: one and the same 20
     * degrees put the crosshair anywhere between 39 and 108 px above the ball over the 2026-11-08
     * session (73 logged poses of level 9), because the camera stands anywhere between 6.7 and
     * 18.7 blocks from the ball and, whenever it falls behind, the ball also drifts off the centre
     * of the frame by up to 26 degrees.
     */
    private static final double GAP_PIXELS_PER_DEGREE = 3.3;

    private static volatile McCameraChannel.Pose framePose;
    private static boolean announced;
    private static boolean mirrorAnnounced;
    private static boolean aspectWarned;
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
        checkFrameAspect(pose);
        McCameraMapper.ensure(pose);
        final double[] eye = McCameraMapper.toBlocks(pose.eye());
        final double[] ball = pose.hasBall() ? McCameraMapper.toBlocks(pose.ball()) : null;
        final float[] yawPitch = aim(McCameraMapper.yawPitch(pose.forward()), pose.aimLift());
        // The rotation the tick will hand the player: solved from the player's own eye when
        // there is one, so the log shows the aim the placement ray will use, not the camera's.
        final Minecraft logClient = Minecraft.getInstance();
        final double[] logEye = logClient.player == null
                ? new double[]{eye[0], eye[1], eye[2]}
                : new double[]{
                        logClient.player.getX(),
                        logClient.player.getY() + logClient.player.getEyeHeight(),
                        logClient.player.getZ()};
        final float[] solved = aimForCrosshair(pose,
                logEye[0], logEye[1], logEye[2], guiWidth(), guiHeight());
        String ballText = ball == null ? "(none)" : String.format("(%.2f, %.2f, %.2f)", ball[0], ball[1], ball[2]);
        if (ball != null && guiWidth() > 0 && guiHeight() > 0) {
            final double[] seen = new View(pose, guiWidth(), guiHeight()).project(ball);
            if (seen != null) {
                ballText = String.format("(%.2f, %.2f, %.2f) at=(%.1f, %.1f) gap=%.1fpx",
                        ball[0], ball[1], ball[2], seen[0], seen[1], gapGui(pose.aimLift()));
            }
        }
        LOGGER.info(String.format(
                "applied pose: eye=(%.2f, %.2f, %.2f) yaw=%.2f pitch=%.2f (lift %.2f)"
                        + " fovH=%.2f -> fovV=%.2f aspect=%.3f frame=%dx%d ball=%s aim=(%.2f, %.2f)",
                eye[0], eye[1], eye[2], yawPitch[0], yawPitch[1], Math.toDegrees(pose.aimLift()),
                Math.toDegrees(pose.fovY()), Math.toDegrees(verticalFov(pose)), pose.aspect(),
                frameWidth(), frameHeight(), ballText, solved[0], solved[1]));
    }

    public static McCameraChannel.Pose framePose() {
        return framePose;
    }

    /**
     * Ballance's field of view as the vertical one Minecraft's projection wants.
     *
     * <p>The channel carries the FOV the Ballance camera is set to, and Virtools takes that
     * number as the <b>horizontal</b> one: {@code VxMatrix::Perspective} puts {@code cot(fov/2)}
     * in {@code m00} (x) and {@code cot(fov/2) * aspect} in {@code m11} (y), so the value spans
     * the width. Minecraft's {@code Camera.calculateFov} is the opposite -- it is vertical, and
     * the projection multiplies by the framebuffer aspect on its own.
     *
     * <p>Feeding 58 straight in therefore opened the Minecraft side up by 58 vertical instead of
     * 58 horizontal, which is {@code aspect} times too wide, and drew the Minecraft world at
     * 1/aspect of its true size: measured on a 640x480 frame, a one-block cube next to the ball
     * came out at 0.56 of the ball's width (vertically 0.75). That is the "two blocks to one
     * ball" the scale report described.
     */
    public static double verticalFov(McCameraChannel.Pose pose) {
        final double aspect = pose.aspect() > 0.01f ? pose.aspect() : 4.0 / 3.0;
        return 2.0 * Math.atan(Math.tan(pose.fovY() * 0.5) / aspect);
    }

    /**
     * The size of the frame this client exports, i.e. what the composite will be stretched to.
     *
     * <p>Only for the log line and the aspect check: the projection itself has to follow the
     * framebuffer aspect for the two halves of the composite to agree, and there is no way to
     * make a 16:9 frame and a 4:3 window agree in both axes at once (see {@link #checkFrameAspect}).
     */
    public static int frameWidth() {
        final Minecraft client = Minecraft.getInstance();
        return client.getWindow() == null ? 0 : client.getWindow().getWidth();
    }

    public static int frameHeight() {
        final Minecraft client = Minecraft.getInstance();
        return client.getWindow() == null ? 0 : client.getWindow().getHeight();
    }

    /**
     * Warns once when the exported frame's aspect differs from the Ballance window's.
     *
     * <p>The composite stretches the frame over the whole window. Two square-pixel projections
     * can only agree about angles in both axes when their aspect ratios match; with 854x480 over
     * 640x480 the horizontal gets the extra squeeze and cubes come out narrow. Setting the
     * window to the same shape is the fix (options.txt overrideWidth/overrideHeight); this is
     * the tripwire that says so instead of leaving it to the eye.
     */
    private static void checkFrameAspect(McCameraChannel.Pose pose) {
        if (aspectWarned) {
            return;
        }
        final int width = frameWidth();
        final int height = frameHeight();
        if (width <= 0 || height <= 0 || pose.aspect() <= 0.01f) {
            return;
        }
        final double frame = (double) width / height;
        if (Math.abs(frame - pose.aspect()) > 0.02) {
            aspectWarned = true;
            LOGGER.warn(String.format(
                    "frame aspect %.3f (%dx%d) differs from the Ballance window's %.3f: the"
                            + " composite stretches one into the other, so the two views cannot"
                            + " match in both axes. Resize the window to %d x %d.",
                    frame, width, height, pose.aspect(), width, (int) Math.round(width / pose.aspect())));
        }
    }

    /** No offset: the crosshair stays exactly where Minecraft draws it. */
    private static final float[] NO_OFFSET = {0.0f, 0.0f};

    /**
     * The camera a frame is drawn from, with the projection that frame uses.
     *
     * <p>The same numbers {@code CameraMixin} installs: the eye through
     * {@link McCameraMapper#toBlocks} (the mapping carries the Z mirror both worlds need), the
     * channel's own forward and up, and the two half-angle tangents -- the horizontal one straight
     * from the channel, the vertical one derived by {@link #verticalFov} because that is the axis
     * Minecraft's projection spans.
     */
    private static final class View {
        private final double[] eye;
        private final double[] forward;
        private final double[] right;
        private final double[] up;
        private final double tanHorizontal;
        private final double tanVertical;
        private final double halfWidth;
        private final double halfHeight;

        private View(McCameraChannel.Pose pose, int guiWidth, int guiHeight) {
            eye = McCameraMapper.toBlocks(pose.eye());
            forward = normalize(pose.forward()[0], pose.forward()[1], -pose.forward()[2]);
            final double[] channelUp = normalize(pose.up()[0], pose.up()[1], -pose.up()[2]);
            right = cross(forward, channelUp);
            up = cross(right, forward);
            tanHorizontal = Math.tan(pose.fovY() * 0.5);
            tanVertical = Math.tan(verticalFov(pose) * 0.5);
            halfWidth = Math.max(1.0, guiWidth * 0.5);
            halfHeight = Math.max(1.0, guiHeight * 0.5);
        }

        /**
         * Where this camera sees a point: GUI pixels from the centre, x to the right and y
         * downwards, the signs {@code HudMixin} translates by. Null behind the camera.
         */
        private double[] project(double[] point) {
            final double[] v = {point[0] - eye[0], point[1] - eye[1], point[2] - eye[2]};
            final double along = dot(v, forward);
            if (along <= 1.0e-6) {
                return null;
            }
            return new double[]{
                    dot(v, right) / along / tanHorizontal * halfWidth,
                    -dot(v, up) / along / tanVertical * halfHeight};
        }

        /** The direction through a GUI pixel offset from the centre, in the client's own space. */
        private double[] through(double offsetX, double offsetY) {
            final double side = offsetX / halfWidth * tanHorizontal;
            final double above = -offsetY / halfHeight * tanVertical;
            return normalize(
                    forward[0] + right[0] * side + up[0] * above,
                    forward[1] + right[1] * side + up[1] * above,
                    forward[2] + right[2] * side + up[2] * above);
        }
    }

    private static double[] normalize(double x, double y, double z) {
        final double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1.0e-9) {
            return new double[]{0.0, 0.0, 1.0};
        }
        return new double[]{x / length, y / length, z / length};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** Minecraft yaw/pitch, in degrees, for a direction in the client's own space. */
    private static float[] angles(double[] direction) {
        final double horizontal = Math.sqrt(direction[0] * direction[0] + direction[2] * direction[2]);
        return new float[]{
                (float) Math.toDegrees(Math.atan2(-direction[0], direction[2])),
                (float) Math.toDegrees(-Math.atan2(direction[1], horizontal))};
    }

    /** The crosshair's height above the ball in GUI pixels, for the panel's aim lift. */
    private static float gapGui(float liftRadians) {
        return (float) (Math.toDegrees(liftRadians) * GAP_PIXELS_PER_DEGREE);
    }

    private static int guiWidth() {
        final Minecraft client = Minecraft.getInstance();
        return client.getWindow() == null ? 0 : client.getWindow().getGuiScaledWidth();
    }

    private static int guiHeight() {
        final Minecraft client = Minecraft.getInstance();
        return client.getWindow() == null ? 0 : client.getWindow().getGuiScaledHeight();
    }

    /**
     * Where the crosshair has to be drawn, in GUI pixels from the centre of the GUI: a fixed
     * number of pixels above the ball, and still exactly on the point the placement ray lands on
     * (2026-11-08).
     *
     * <p>Since 2026-11-07 this projected the aim point -- the ball, plus the eye height, plus the
     * reach along the aim -- through the camera. That made the crosshair the landing point by
     * construction, but the distance from it to the ball was whatever the camera's distance and
     * its lag happened to make it: measured 39..108 GUI px over one session, for one and the same
     * aim lift, which is what "the crosshair does not follow the ball" looked like. It now
     * projects the ball itself and lifts the result by {@link #gapGui}, so the two are a fixed
     * distance apart, and the aim is solved to match (see {@link #aimForCrosshair}) so that the
     * block still lands under the crosshair.
     *
     * <p>Returns {0, 0} when there is no pose yet, so the stock crosshair is left alone.
     */
    public static float[] crosshairOffsetGui(int guiWidth, int guiHeight) {
        final McCameraChannel.Pose pose = framePose;
        if (pose == null || !pose.valid() || guiWidth <= 0 || guiHeight <= 0 || pose.fovY() <= 0.0f) {
            return NO_OFFSET;
        }
        if (!McCameraMapper.ready()) {
            return NO_OFFSET;
        }
        final Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return NO_OFFSET;
        }
        if (!pose.hasBall()) {
            // Nothing to hold the crosshair against: fall back to the aim point itself.
            return aimPointOffset(pose, guiWidth, guiHeight);
        }
        final View view = new View(pose, guiWidth, guiHeight);
        final double[] ball = view.project(McCameraMapper.toBlocks(pose.ball()));
        if (ball == null) {
            return NO_OFFSET;
        }
        return new float[]{(float) ball[0], (float) (ball[1] - gapGui(pose.aimLift()))};
    }

    /**
     * Where the placement ray lands, in GUI pixels from the centre -- the projection this used
     * before the crosshair was pinned to the ball, kept for the frames that carry no ball.
     */
    private static float[] aimPointOffset(McCameraChannel.Pose pose, int guiWidth, int guiHeight) {
        final Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return NO_OFFSET;
        }
        final View view = new View(pose, guiWidth, guiHeight);
        final float[] yawPitch = aimForCrosshair(pose,
                client.player.getX(), client.player.getY() + client.player.getEyeHeight(),
                client.player.getZ(), guiWidth, guiHeight);
        final double yaw = Math.toRadians(yawPitch[0]);
        final double pitch = Math.toRadians(yawPitch[1]);
        final double cosPitch = Math.cos(pitch);
        final double eyeX = client.player.getX();
        final double eyeY = client.player.getY() + client.player.getEyeHeight();
        final double eyeZ = client.player.getZ();
        final double[] landing = {
                eyeX + -Math.sin(yaw) * cosPitch * REACH_BLOCKS,
                eyeY + -Math.sin(pitch) * REACH_BLOCKS,
                eyeZ + Math.cos(yaw) * cosPitch * REACH_BLOCKS};
        final double[] seen = view.project(landing);
        if (seen == null) {
            return NO_OFFSET;
        }
        return new float[]{(float) seen[0], (float) seen[1]};
    }

    /**
     * The rotation the player has to carry so that the placement ray lands a fixed
     * {@link #gapGui} pixels above the ball's own projected position (2026-11-08).
     *
     * <p>Why solve at all: an angle of lift is a rotation, and how many pixels it moves the
     * crosshair depends on how far the camera stands -- a far camera shrinks it, and a camera that
     * has fallen behind the ball puts the ball off the centre as well, so the same lift drew the
     * crosshair anywhere between 39 and 108 px above the ball in one session.
     *
     * <p>The solve is exact, not iterative. The landing point has to satisfy two things: it lies
     * on the camera's ray through the crosshair (so the crosshair is drawn on it), and it is
     * exactly {@link #REACH_BLOCKS} from the player's eye (so the ray that starts there lands on
     * it). The second is a sphere around the eye, so the point is where that ray meets the
     * sphere -- a quadratic. The far crossing is the one wanted: it is the one in front of the
     * ball, the near one would put the block between the ball and the camera.
     */
    public static float[] aimForCrosshair(McCameraChannel.Pose pose,
            double eyeX, double eyeY, double eyeZ, int guiWidth, int guiHeight) {
        final float[] fallback = aim(McCameraMapper.yawPitch(pose.forward()), pose.aimLift());
        if (guiWidth <= 0 || guiHeight <= 0 || pose.fovY() <= 0.0f || !pose.hasBall()) {
            return fallback;
        }
        final View view = new View(pose, guiWidth, guiHeight);
        final double[] ball = view.project(McCameraMapper.toBlocks(pose.ball()));
        if (ball == null) {
            return fallback;
        }
        final double[] ray = view.through(ball[0], ball[1] - gapGui(pose.aimLift()));
        final double[] fromEye = {view.eye[0] - eyeX, view.eye[1] - eyeY, view.eye[2] - eyeZ};
        final double b = dot(fromEye, ray);
        final double c = dot(fromEye, fromEye) - REACH_BLOCKS * REACH_BLOCKS;
        final double discriminant = b * b - c;
        if (discriminant < 0.0) {
            // The pixel is further from the eye than the reach can go -- a camera that has fallen
            // a long way off. Keep the old fixed-angle lift rather than aim at nothing.
            return fallback;
        }
        final double t = -b + Math.sqrt(discriminant);
        if (!(t > 0.0)) {
            return fallback;
        }
        return angles(normalize(
                view.eye[0] + ray[0] * t - eyeX,
                view.eye[1] + ray[1] * t - eyeY,
                view.eye[2] + ray[2] * t - eyeZ));
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
        // The angles solved for the crosshair, not the camera's own: the block interaction ray
        // starts at the player's eye along this rotation, so this is what makes the block land
        // under the crosshair instead of wherever a fixed lift happened to point.
        final float[] yawPitch = aimForCrosshair(pose, x, y + player.getEyeHeight(), z,
                guiWidth(), guiHeight());
        player.setYRot(yawPitch[0]);
        player.setXRot(yawPitch[1]);
        player.setOldPosAndRot();
        mirrorToServer(client, player, x, y, z, yawPitch[0], yawPitch[1]);

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

    /**
     * Puts the same eye on the server side of the integrated server (2026-11-08).
     *
     * <p>The client teleports itself onto the ball every tick; the server's copy of the player is
     * only ever moved by movement packets, and those stop being applied as soon as the server is
     * still waiting for an earlier teleport to be acknowledged -- which the next tick's snapTo
     * guarantees never happens. Measured 2026-11-08: the server eye sat 8.0 blocks behind the
     * client's and stayed there for tens of seconds.
     *
     * <p>That is the whole of "a block flashes for one frame and is then gone": if the crosshair
     * is on a block, the vanilla placement path is used, the server finds the target out of reach
     * from its own eye, refuses, and the client takes the block back. Placed into empty air the
     * same click works, because {@code PassthroughInput.placeAtAim} writes those straight into
     * the server level and never asks the player position.
     *
     * <p>No packet is involved: this is a single-player server in the same process, so the entity
     * is simply put where the client is, and reach and line of sight are then measured from the
     * eye the crosshair was drawn for.
     */
    private static void mirrorToServer(Minecraft client, LocalPlayer player,
            double x, double y, double z, float yaw, float pitch) {
        final IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        final ServerPlayer mirrored = server.getPlayerList().getPlayer(player.getUUID());
        if (mirrored == null) {
            return;
        }
        mirrored.setPos(x, y, z);
        mirrored.setYRot(yaw);
        mirrored.setXRot(pitch);
        mirrored.setDeltaMovement(Vec3.ZERO);
        if (!mirrorAnnounced) {
            mirrorAnnounced = true;
            LOGGER.info("camera sync: the integrated server's copy of the player follows the ball");
        }
    }

    // ------------------------------------------------------------------ aim watch

    private static String lastHitLabel;
    private static int hitChanges;
    private static long hitWindowNanos;

    /**
     * Counts how often the block under the crosshair changes, and says so once a second when
     * it changed more often than a player could be re-aiming (2026-11-08).
     *
     * <p>The user's report was a blinking selection box. That box is drawn for
     * {@code Minecraft.hitResult}, so a measurement of how that value moves is the difference
     * between "the aim really is jumping" and "the aim is steady and the picture is what
     * flickers" -- two different bugs with two different fixes.
     */
    public static void watchHitTarget(Minecraft client) {
        final HitResult hit = client.hitResult;
        final String label = hit == null ? "null"
                : hit instanceof BlockHitResult block ? String.valueOf(block.getBlockPos())
                : hit.getType().name();
        if (!label.equals(lastHitLabel)) {
            lastHitLabel = label;
            ++hitChanges;
        }
        final long now = System.nanoTime();
        if (hitWindowNanos == 0L) {
            hitWindowNanos = now;
            return;
        }
        final long span = now - hitWindowNanos;
        if (span < 1_000_000_000L) {
            return;
        }
        if (hitChanges >= 8 && client.player != null) {
            LOGGER.info("aim target changed {} times in {} ms, now {} player=({},{},{}) yaw={} pitch={}",
                    hitChanges, span / 1_000_000L, label,
                    String.format("%.2f", client.player.getX()),
                    String.format("%.2f", client.player.getY()),
                    String.format("%.2f", client.player.getZ()),
                    String.format("%.2f", client.player.getYRot()),
                    String.format("%.2f", client.player.getXRot()));
        }
        hitWindowNanos = now;
        hitChanges = 0;
    }
}
