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

    /**
     * True when the worlds are baked in absolute Ballance coordinates (2026-11-08).
     *
     * <p>Pre-baked level worlds have their blocks at {@code floor(units / cell)}, so the mapping
     * cannot be re-anchored per level any more: the origin is the world origin, and only the
     * vertical offset is free. Re-anchoring on a baked world would shift the level by up to a
     * block depending on where the ball happened to be at the start, which is exactly the kind of
     * half-block error the import exists to avoid.
     */
    private static boolean absolute;

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
        if (absolute) {
            unitsPerBlock = scale;
            originX = 0.0;
            originY = 0.0;
            originZ = 0.0;
            ready = true;
            return;
        }
        if (ready) {
            if (scale > 0.0f && Math.abs(scale - unitsPerBlock) > 1.0e-4f) {
                unitsPerBlock = scale;
            }
            return;
        }
        final double[] reference = pose.hasBall() ? pose.ball() : pose.eye();
        unitsPerBlock = scale > 0.0f ? scale : 4.0f;
        originX = snap(reference[0], unitsPerBlock);
        originY = snap(reference[1], unitsPerBlock);
        originZ = snap(reference[2], unitsPerBlock);
        ready = true;
    }

    /**
     * Rounds a world coordinate down to a multiple of the block size.
     *
     * <p>The origin is where the two worlds are pinned together, and it is also where the
     * imported level's cell grid has to land (see {@link LevelVoxels}): a cell's index is
     * {@code floor(units / cell)}, so an origin that is not itself on that grid would put every
     * imported block a fraction of a cell off. Snapping costs at most one block of offset
     * between the ball and the anchor, and makes the two grids the same grid.
     */
    private static double snap(double value, double cell) {
        return cell <= 0.0 ? value : Math.floor(value / cell) * cell;
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
        if (absolute) {
            // A baked world already sits where the level is; nothing to move.
            if (pose.unitsPerBlock() > 0.0f) {
                unitsPerBlock = pose.unitsPerBlock();
            }
            originX = 0.0;
            originY = 0.0;
            originZ = 0.0;
            ready = true;
            return;
        }
        final double[] reference = pose.hasBall() ? pose.ball() : pose.eye();
        if (pose.unitsPerBlock() > 0.0f) {
            unitsPerBlock = pose.unitsPerBlock();
        }
        originX = snap(reference[0], unitsPerBlock);
        originY = snap(reference[1], unitsPerBlock);
        originZ = snap(reference[2], unitsPerBlock);
        ready = true;
    }

    /**
     * Switches to absolute (pre-baked) coordinates and puts the anchor where the level wants it.
     *
     * <p>Only {@code anchorY} stays free: it is the vertical offset between a level's cell index
     * and the world's build height, and it is the same number the template was baked with.
     */
    public static synchronized void setAbsolute(double anchorYBlocks) {
        absolute = true;
        originX = 0.0;
        originY = 0.0;
        originZ = 0.0;
        anchorX = 0.0;
        anchorY = anchorYBlocks;
        anchorZ = 0.0;
        ready = true;
    }

    public static synchronized boolean absolute() {
        return absolute;
    }

    /** The block coordinate the Ballance origin maps to (the ball starts here). */
    public static synchronized double[] anchor() {
        return new double[]{anchorX, anchorY, anchorZ};
    }

    /**
     * Moves the anchor vertically. The import needs it: the anchor is the ball, and a level that
     * is tall (level 13 is 368 cells) would otherwise reach out of the world's build height.
     */
    public static synchronized void setAnchorY(double y) {
        anchorY = y;
    }

    /** The origin as whole cells, i.e. what a baked voxel index is relative to. */
    public static synchronized long[] originIndex(double cell) {
        return new long[]{
                (long) Math.floor(originX / cell),
                (long) Math.floor(originY / cell),
                (long) Math.floor(originZ / cell),
        };
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
