package com.example.ballance.passthrough;

/**
 * Ballance world units -&gt; Minecraft block coordinates, for the M1 camera sync.
 *
 * <p>Scale: one Minecraft block is four Ballance units (docs/scale-calibration.md: the ball is
 * 4.0 units across, i.e. exactly one block). The channel carries its own UnitsPerBlock, so a
 * future change there does not have to be repeated in two places.
 *
 * <p>Axes: both worlds are Y-up, but Virtools is left handed (+Z forward) and Minecraft is right
 * handed, so Z is mirrored. That keeps "forward" meaning forward: a Ballance +Z view direction
 * becomes a Minecraft -Z one, which is what Minecraft calls north and what yaw 0 does not point
 * at -- see {@link #yawPitch}, which derives the angles from the vector instead of assuming.
 *
 * <p>Origin: the two worlds share no coordinates, so the first sample defines the mapping. The
 * ball's position then becomes the anchor (BALLANCE_MC_ANCHOR, default 0,100,0), and everything
 * else keeps its relative placement. Without that, a level whose geometry sits around
 * z = -153 would land a few hundred blocks from spawn and the client would show void.
 */
public final class McCameraMapper {
    private static boolean ready;
    private static double originX;
    private static double originY;
    private static double originZ;
    private static double anchorX;
    private static double anchorY = 100.0;
    private static double anchorZ;
    private static float unitsPerBlock = 4.0f;

    static {
        final String spec = System.getenv("BALLANCE_MC_ANCHOR");
        if (spec != null && !spec.isBlank()) {
            final String[] parts = spec.split(",");
            if (parts.length == 3) {
                try {
                    anchorX = Double.parseDouble(parts[0].trim());
                    anchorY = Double.parseDouble(parts[1].trim());
                    anchorZ = Double.parseDouble(parts[2].trim());
                } catch (NumberFormatException ignored) {
                    // keep the defaults; a bad anchor must not stop the sync
                }
            }
        }
    }

    private McCameraMapper() {
    }

    /**
     * Locks the origin in on the first usable sample, and keeps the scale current.
     *
     * <p>The origin has to be locked: it is the correspondence between the two worlds, and
     * re-deriving it would make the world jump. The scale is different -- it is a single number
     * the Ballance side owns, and freezing it on the first frame silently ignored every later
     * change (a whole acceptance run rendered at the old value because the client had been
     * started before the rebuild). Cheap to keep current, so it is re-read every sample.
     */
    public static synchronized void ensure(McCameraChannel.Pose pose) {
        final float scale = pose.unitsPerBlock() > 0.0f ? pose.unitsPerBlock() : unitsPerBlock;
        if (ready) {
            if (scale > 0.0f && Math.abs(scale - unitsPerBlock) > 1.0e-4f) {
                unitsPerBlock = scale;
            }
            return;
        }
        final double[] reference = pose.hasBall() ? pose.ball() : pose.eye();
        unitsPerBlock = scale > 0.0f ? scale : 4.0f;
        originX = reference[0];
        originY = reference[1];
        originZ = reference[2];
        ready = true;
    }

    /**
     * Moves the mapping origin onto this sample, so the ball it carries maps to the anchor again.
     *
     * <p>Called when a level starts. Ballance levels live at their own coordinates (level 1's ball
     * sits at z = -153, level 8's somewhere else entirely), so keeping the first level's origin
     * would land every later level hundreds of blocks away from the anchor -- and from the chunks
     * the client already has. Every level therefore begins at the anchor, in a world of its own.
     */
    public static synchronized void reanchor(McCameraChannel.Pose pose) {
        if (pose == null) {
            return;
        }
        final double[] reference = pose.hasBall() ? pose.ball() : pose.eye();
        if (pose.unitsPerBlock() > 0.0f) {
            unitsPerBlock = pose.unitsPerBlock();
        }
        originX = reference[0];
        originY = reference[1];
        originZ = reference[2];
        ready = true;
    }

    /** Serialises the origin for the log: "origin moved" is a diff, not an opinion. */
    public static synchronized String origin() {
        return String.format("(%.2f, %.2f, %.2f)", originX, originY, originZ);
    }

    public static boolean ready() {
        return ready;
    }

    public static double[] toBlocks(double[] units) {
        final double scale = 1.0 / unitsPerBlock;
        return new double[]{
                anchorX + (units[0] - originX) * scale,
                anchorY + (units[1] - originY) * scale,
                anchorZ - (units[2] - originZ) * scale,
        };
    }

    /**
     * Minecraft yaw/pitch, in degrees, for a mapped look direction.
     *
     * <p>Minecraft's convention (players and camera alike): yaw 0 looks at +Z, yaw increases
     * clockwise seen from above, and pitch is positive downwards. So for a unit direction d,
     * yaw = atan2(-d.x, d.z) and pitch = -asin(d.y).
     */
    public static float[] yawPitch(double[] ballanceForward) {
        final double dx = ballanceForward[0];
        final double dy = ballanceForward[1];
        final double dz = -ballanceForward[2];
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        final float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        final float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }

    public static String describe() {
        return String.format("anchor=(%.1f, %.1f, %.1f) origin=(%.1f, %.1f, %.1f) scale=1/%.1f",
                anchorX, anchorY, anchorZ, originX, originY, originZ, unitsPerBlock);
    }
}
