package com.example.ballance.passthrough;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The level's own surfaces, imported as invisible support blocks (2026-11-08).
 *
 * <p>Why: the passthrough world is a void, and nothing in it can carry a block. Redstone dust,
 * rails, torches, saplings -- everything vanilla asks for a sturdy face underneath -- simply
 * refuses to be placed, which is what the user hit when they tried to lay redstone on the
 * Ballance floor. The floor they can see is Ballance's, drawn by Ballance; Minecraft has no idea
 * it exists.
 *
 * <p>So the level's geometry is read out of the game files (tools/voxel/voxelize.py, which
 * voxelises tools/nmo_geom's world-space export), and every cell becomes a
 * {@code minecraft:barrier}: a full cube that renders nothing. The picture does not change at
 * all, but the world now has a floor exactly where the picture shows one, and vanilla placement,
 * support and ray hits all work.
 *
 * <p>Coordinates are absolute: a cell index is {@code floor(units / cell)}, one block per cell,
 * with the Z axis mirrored the way {@link McCameraMapper} maps it. That is what lets a level be
 * baked into a save once ({@code BALLANCE_MC_BAKE=<n>}) and shipped: the world already contains
 * the level, so a level start only has to copy it.
 *
 * <p>Which level: the game says so (M1c, 2026-11-08). The number in the level file Ballance
 * loaded travels in the camera channel as {@link McCameraChannel.Pose#levelId()}. Until then the level was guessed from the ball's
 * position, which ties on the five levels that share a start floor (3, 5, 7, 9, 11) -- and a tie
 * meant no level at all. The position is kept as the fallback, for a client that meets an older
 * Player.exe.
 *
 * <p>Off with {@code BALLANCE_MC_VOXELS=0}. {@code BALLANCE_MC_LEVEL=<1..13>} forces a level.
 */
public final class LevelVoxels {
    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-voxel");

    private static final boolean ENABLED = !"0".equals(System.getenv("BALLANCE_MC_VOXELS"));

    /** BALLANCE_MC_PROBE=1: try placing redstone through vanilla before logging the floor. */
    private static final boolean PROBE = "1".equals(System.getenv("BALLANCE_MC_PROBE"));

    private static final int LEVEL_COUNT = 13;

    /**
     * How close the winning level has to be, and how far ahead of the runner-up.
     *
     * <p>Measured on level 1's start: the own level scores 0.64 cells, the next best 2.8. A flat
     * limit would break for a level whose ball starts higher above its floor, so the match also
     * has to win by a margin -- a wrong match would load a whole other level's world.
     */
    private static final double MATCH_CELLS = 4.0;
    private static final double MATCH_LEAD = 0.6;

    private static final int CELLS_PER_TICK = 2048;

    private static final int MIN_Y = -62;
    private static final int MAX_Y = 318;

    /** Vertical offset of a level that is not baked yet: the ball sits at y=100. */
    private static final int DEFAULT_OFFSET_Y = 100;

    private static final VoxelSet[] SETS = new VoxelSet[LEVEL_COUNT + 1];

    private static Object worldToken;
    private static VoxelSet active;
    private static int activeOffsetY;
    private static boolean[] done;
    private static int cursor;
    private static int placed;
    private static int cleared;
    private static boolean summarised;
    private static boolean probeLogged;

    /** Ticks until the "what is under the ball" line; 0 = already logged or not requested. */
    private static int floorCheckIn;

    /** One bake step at a time: the work runs on the server thread, not the render thread. */
    private static boolean bakeStepPending;

    /** {@code BALLANCE_MC_BAKE=<n>}: write level n into the current world and save it. */
    private static final int BAKE_LEVEL = readInt("BALLANCE_MC_BAKE");
    private static boolean baked;

    private LevelVoxels() {
    }

    /** One baked level: cell indices relative to (minI, minJ, minK). */
    private record VoxelSet(int id, double cell, int minI, int minJ, int minK,
                            int dimI, int dimJ, int dimK, int count, int walkCount, short[] rel) {

        int i(int n) {
            return minI + rel[3 * n];
        }

        int j(int n) {
            return minJ + rel[3 * n + 1];
        }

        int k(int n) {
            return minK + rel[3 * n + 2];
        }

        /**
         * Distance, in cells, from the ball to the closest *walkable* cell below it.
         *
         * <p>Walkable only, and below only: a level that merely has a wall passing nearby must
         * not score as "the ball stands here", and the ball stands on the floor, not on a wall.
         */
        double distance(double[] ballUnits) {
            final double ci = ballUnits[0] / cell;
            final double cj = ballUnits[1] / cell;
            final double ck = ballUnits[2] / cell;
            double best = Double.MAX_VALUE;
            for (int n = 0; n < walkCount; ++n) {
                if (j(n) > cj + 1.5) {
                    continue;
                }
                final double dx = i(n) + 0.5 - ci;
                final double dy = j(n) + 0.5 - cj;
                final double dz = k(n) + 0.5 - ck;
                final double d = dx * dx + dy * dy + dz * dz;
                if (d < best) {
                    best = d;
                }
            }
            return best == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(best);
        }

        String describe() {
            return String.format("Level_%02d: %d cells (%d walkable), %dx%dx%d",
                    id, count, walkCount, dimI, dimJ, dimK);
        }
    }

    // ------------------------------------------------------------------ level identity

    /**
     * Identifies the level the ball is standing in and pins the mapping to absolute coordinates.
     *
     * <p>Called at a level start, before the world is created, because the answer decides which
     * baked world to copy.
     *
     * <p>Since M1c (2026-11-08) the answer comes from the game itself: the number in the level
     * file it loaded, which the Ballance side publishes in the camera channel. It used to
     * be derived from where the ball stood -- "the level whose walkable floor passes closest
     * under the ball" -- and that cannot be relied on: levels 3, 5, 7, 9 and 11 share their
     * start floor (measured 2026-11-08: all five scored 0.7 cells), so the vote ties and no
     * level was loaded. The position is kept as the fallback, for a Ballance build that does
     * not publish the number.
     *
     * @param ballUnits the player ball, in Ballance units
     * @param levelId   the level the game loaded, from the same sample; 0 = unknown
     * @return 1..13, or 0 when nothing is known well enough (then the caller should fall back)
     */
    public static int identify(double[] ballUnits, int levelId) {
        if (!ENABLED) {
            return 0;
        }
        // BALLANCE_MC_LEVEL still wins: it is how the templates were baked and how a
        // mislabelled level is tested without touching the game.
        final int forced = readInt("BALLANCE_MC_LEVEL");
        final int named = forced > 0 ? forced : levelId;
        if (named > 0 && named <= LEVEL_COUNT) {
            if (set(named) == null) {
                LOGGER.warn("voxels: level {} has no baked data in this build ({}); falling back"
                        + " to the ball position", named,
                        forced > 0 ? "BALLANCE_MC_LEVEL" : "the game says so");
            } else if (adopt(named, forced > 0
                    ? "BALLANCE_MC_LEVEL=" + forced
                    : "the game's own level id")) {
                return named;
            } else {
                // The data is there but was baked for another block size: that is not a
                // guess to be patched up with a different level.
                return 0;
            }
        } else if (named > LEVEL_COUNT) {
            // A custom map, or a game whose counter means something else. Not an error.
            LOGGER.info("voxels: the game says level {}", named);
        }
        return identifyByPosition(ballUnits);
    }

    /** Takes one level as the answer: checks the block size, pins the anchor, says why. */
    private static boolean adopt(int id, String why) {
        final VoxelSet set = set(id);
        final double cell = currentCell();
        if (Math.abs(set.cell - cell) > 1.0e-3) {
            LOGGER.warn(String.format(
                    "voxels: level %d is baked for a %.2f u block but the panel says %.2f u; the"
                            + " import needs re-baking at the new size. Not placing.",
                    id, set.cell, cell));
            return false;
        }
        activeOffsetY = offsetY(set);
        McCameraMapper.setAbsolute(activeOffsetY);
        active = set;
        // The placement state belongs to the set it was allocated for. Adopting a level here
        // and leaving the old array behind used to index past its end one tick later
        // (2026-11-08 15:02:45: Index 7206 out of bounds for length 7206 -- level 3 adopted
        // while level 6's done[] was still in place, and the scan came back round to 7206).
        // Whatever is half-placed is dropped: the caller either places this level (see
        // beginPlacement) or uses a baked world that already has its blocks.
        done = null;
        cursor = 0;
        placed = 0;
        cleared = 0;
        summarised = false;
        LOGGER.info("voxels: level {} from {} ({} cells), anchor y {}.",
                id, why, set.count, activeOffsetY);
        return true;
    }

    /**
     * The old answer: the level whose walkable floor passes closest under the ball.
     *
     * <p>Kept because a client can be newer than the Player.exe it runs against, and because
     * it is the independent check that the published number is not a lie -- the two agreeing
     * is worth knowing, and the scores are logged either way.
     */
    private static int identifyByPosition(double[] ballUnits) {
        VoxelSet best = null;
        double bestScore = Double.MAX_VALUE;
        double secondScore = Double.MAX_VALUE;
        final StringBuilder scores = new StringBuilder();
        for (int id = 1; id <= LEVEL_COUNT; ++id) {
            final VoxelSet set = set(id);
            if (set == null) {
                continue;
            }
            final double score = set.distance(ballUnits);
            scores.append(String.format(" %d=%.1f", id, score));
            if (score < bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = set;
            } else if (score < secondScore) {
                secondScore = score;
            }
        }
        if (best == null) {
            LOGGER.warn("voxels: no baked level found in the mod's resources");
            return 0;
        }
        if (bestScore > MATCH_CELLS || bestScore > MATCH_LEAD * secondScore) {
            LOGGER.warn("voxels: no level is under the ball (closest {} at {} cells, runner-up {};"
                            + " limit {} / lead {}); scores:{}", best.id,
                    String.format("%.1f", bestScore), String.format("%.1f", secondScore),
                    MATCH_CELLS, MATCH_LEAD, scores);
            return 0;
        }
        return adopt(best.id, String.format("the ball position (%.2f cells, runner-up %.2f;"
                + " scores:%s)", bestScore, secondScore, scores)) ? best.id : 0;
    }

    /** The vertical offset this level was baked with (see {@link #offsetY(VoxelSet)}). */
    public static int offsetY(int levelId) {
        final VoxelSet set = set(levelId);
        return set == null ? DEFAULT_OFFSET_Y : offsetY(set);
    }

    private static int offsetY(VoxelSet set) {
        final int lo = set.minJ;
        final int hi = set.minJ + set.dimJ - 1;
        final int needLo = MIN_Y + 2 - lo;
        final int needHi = MAX_Y - 2 - hi;
        if (needLo > needHi) {
            LOGGER.warn("voxels: level {} spans {} cells, taller than the build height;"
                    + " cells outside y={}..{} are skipped", set.id, set.dimJ, MIN_Y, MAX_Y);
            return (needLo + needHi) / 2;
        }
        return Math.max(needLo, Math.min(needHi, DEFAULT_OFFSET_Y));
    }

    // ------------------------------------------------------------------ placement

    /** Asks for a one-shot log of the block under the ball, ten ticks from now. */
    public static void checkFloorSoon() {
        floorCheckIn = 10;
    }

    /** Starts writing the active level's cells into whatever world is loaded. */
    public static void beginPlacement(int levelId) {
        final VoxelSet set = set(levelId);
        if (set == null) {
            return;
        }
        active = set;
        activeOffsetY = offsetY(set);
        McCameraMapper.setAbsolute(activeOffsetY);
        done = new boolean[set.count];
        cursor = 0;
        placed = 0;
        cleared = 0;
        summarised = false;
        probeLogged = false;
        LOGGER.info("voxels: placing {} ({})", set.describe(),
                McWorldBootstrap.currentWorldId());
    }

    public static void onClientTick(Minecraft client) {
        if (!ENABLED || client.level == null) {
            return;
        }
        if (BAKE_LEVEL > 0 && !baked) {
            bakeSome(client);
            return;
        }
        if (active != null && floorCheckIn > 0 && --floorCheckIn == 0) {
            logFloor(client);
        }
        if (active == null || done == null) {
            return;
        }
        if (client.level != worldToken) {
            worldToken = client.level;
        }
        placeSome(client);
    }

    private static void placeSome(Minecraft client) {
        final IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        final ServerLevel level = server.getLevel(client.level.dimension());
        if (level == null) {
            return;
        }
        final VoxelSet set = active;
        // A thrown-away array must never be read with a live set's index, whatever route put
        // the mismatch here (see adopt).
        if (done == null || done.length != set.count) {
            done = new boolean[set.count];
            cursor = 0;
            placed = 0;
            cleared = 0;
            summarised = false;
        }
        // Every cell visited and nothing left to retry: walking 7000 entries per tick for ever
        // would only cost the server thread.
        if (cleared >= set.count) {
            return;
        }
        final var state = Blocks.BARRIER.defaultBlockState();
        int budget = CELLS_PER_TICK;
        int scanned = 0;
        while (scanned < set.count && budget > 0) {
            final int n = cursor;
            cursor = (cursor + 1) % set.count;
            ++scanned;
            if (done[n]) {
                continue;
            }
            final BlockPos pos = cellPos(set, n, activeOffsetY);
            if (pos.getY() < MIN_Y || pos.getY() > MAX_Y) {
                done[n] = true;
                continue;
            }
            if (!level.hasChunkAt(pos)) {
                continue;   // far away: the ring scan comes back when the ball gets there
            }
            if (level.setBlock(pos, state, 2)) {
                ++placed;
                --budget;
            }
            done[n] = true;
            ++cleared;
            if (!probeLogged) {
                probeLogged = true;
                probeRedstone(level, pos);
            }
        }
        if (!summarised && cleared >= set.count) {
            summarised = true;
            // placed can be smaller than the cell count: a template world already has them,
            // and setBlock returns false where the block is already there.
            LOGGER.info("voxels: {} of {} barrier cells placed in \"{}\" (the rest were there)",
                    placed, set.count, McWorldBootstrap.currentWorldId());
        }
    }

    /** A cell's block position: absolute x, offset y, mirrored z (the composer's convention). */
    static BlockPos cellPos(VoxelSet set, int n, int offsetY) {
        return new BlockPos(set.i(n), set.j(n) + offsetY, -set.k(n) - 1);
    }

    /**
     * What the world thinks is under the ball. The import is only worth anything if this is a
     * barrier: it is the block a redstone dust would be laid on.
     */
    private static void logFloor(Minecraft client) {
        final IntegratedServer server = client.getSingleplayerServer();
        final McCameraChannel.Pose pose = McCameraChannel.read();
        if (server == null || client.level == null || pose == null || !pose.hasBall()) {
            return;
        }
        final ServerLevel level = server.getLevel(client.level.dimension());
        if (level == null) {
            return;
        }
        final double[] ball = McCameraMapper.toBlocks(pose.ball());
        final BlockPos base = BlockPos.containing(ball[0], ball[1], ball[2]);
        // The floor is at or below the ball, by up to one cell (the vertical rule keeps the
        // block's top under the surface). Look a few blocks down for it.
        for (int depth = 0; depth <= 3; ++depth) {
            final BlockPos pos = base.below(depth);
            if (!level.getBlockState(pos).is(Blocks.BARRIER)) {
                continue;
            }
            LOGGER.info("voxels: under the ball at {} ({} below) is {}", pos, depth,
                    level.getBlockState(pos));
            if (PROBE) {
                probeVanillaPlacement(level, pos);
            }
            return;
        }
        LOGGER.info("voxels: no barrier within 3 blocks below the ball at {}", base);
    }

    /**
     * Asks vanilla to place a redstone dust on top of a barrier, the way a right click does.
     *
     * <p>This is the user's actual complaint, tested end to end instead of by inspection:
     * canSurvive is the rule that decides it, but going through gameMode.useItemOn is the code
     * path the mouse button takes, so nothing here is an assumption about what that rule is
     * applied to. Only runs with BALLANCE_MC_PROBE=1, because it really does place a block.
     */
    private static void probeVanillaPlacement(ServerLevel level, BlockPos support) {
        try {
            final var server = level.getServer();
            if (server.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            final var player = server.getPlayerList().getPlayers().get(0);
            final BlockPos target = support.above();
            final var hit = new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(support).add(0.0, 0.5, 0.0),
                    net.minecraft.core.Direction.UP, support, false);
            final boolean survives = Blocks.REDSTONE_WIRE.defaultBlockState()
                    .canSurvive(level, target);
            LOGGER.info("voxels: probe: gamemode={} target={}={} canSurvive={} reach={}",
                    player.gameMode.getGameModeForPlayer(), target, level.getBlockState(target),
                    survives, player.blockPosition().distSqr(target));
            final var result = player.gameMode.useItemOn(player, level,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.REDSTONE),
                    net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            final boolean placed = level.getBlockState(target).is(Blocks.REDSTONE_WIRE);
            LOGGER.info("voxels: vanilla right-click test on a barrier -> {} (redstone placed: {})",
                    result, placed);
            if (!placed) {
                // Which half refused: the placement rules, or redstone's own support rule.
                final boolean forced = level.setBlock(target,
                        Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
                LOGGER.info("voxels: probe: setBlock(redstone) forced={} now={}",
                        forced, level.getBlockState(target));
            }
        } catch (Throwable throwable) {
            LOGGER.warn("voxels: vanilla placement probe failed", throwable);
        }
    }

    private static void probeRedstone(ServerLevel level, BlockPos pos) {
        try {
            final boolean survives = Blocks.REDSTONE_WIRE.defaultBlockState()
                    .canSurvive(level, pos.above());
            LOGGER.info("voxels: redstone dust {} survive on a barrier at {}",
                    survives ? "can" : "CANNOT", pos.above());
        } catch (Throwable throwable) {
            LOGGER.warn("voxels: redstone probe failed", throwable);
        }
    }

    // ------------------------------------------------------------------ baking

    /**
     * Writes the level into the current world, forcing the chunks it needs, then saves.
     *
     * <p>This is how the shipped templates are made: run the client with
     * {@code BALLANCE_MC_BAKE=<n> BALLANCE_MC_VOID_WORLD=bmc-tpl-<n>}, wait for "BAKE DONE",
     * and take the save folder. Placement normally skips chunks that are not loaded; a template
     * must not, or it would be missing the far end of the level.
     */
    private static void bakeSome(Minecraft client) {
        if (bakeStepPending) {
            return;
        }
        final IntegratedServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        // Chunk generation and saving are the server's; doing either from the client tick
        // thread crashed the game (DistanceManager.runAllUpdates NPE on saveEverything).
        bakeStepPending = true;
        server.execute(() -> {
            try {
                bakeStep(client, server);
            } catch (Throwable throwable) {
                LOGGER.error("voxels: bake step failed", throwable);
                baked = true;
            } finally {
                bakeStepPending = false;
            }
        });
    }

    private static void bakeStep(Minecraft client, IntegratedServer server) {
        final ServerLevel level = server.getLevel(client.level.dimension());
        if (level == null) {
            return;
        }
        final VoxelSet set = set(BAKE_LEVEL);
        if (set == null) {
            LOGGER.error("voxels: BAKE={} but no data for it", BAKE_LEVEL);
            baked = true;
            return;
        }
        final int offsetY = offsetY(set);
        final var state = Blocks.BARRIER.defaultBlockState();
        if (done == null) {
            done = new boolean[set.count];
            cursor = 0;
            placed = 0;
            active = set;
            activeOffsetY = offsetY;
            McCameraMapper.setAbsolute(offsetY);
            LOGGER.info("voxels: baking {} with anchor y {}", set.describe(), offsetY);
        }
        int budget = CELLS_PER_TICK;
        int scanned = 0;
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        while (scanned < set.count && budget > 0) {
            final int n = cursor;
            cursor = (cursor + 1) % set.count;
            ++scanned;
            if (done[n]) {
                continue;
            }
            final BlockPos pos = cellPos(set, n, offsetY);
            if (pos.getY() < MIN_Y || pos.getY() > MAX_Y) {
                done[n] = true;
                continue;
            }
            if ((pos.getX() >> 4) != lastCx || (pos.getZ() >> 4) != lastCz) {
                lastCx = pos.getX() >> 4;
                lastCz = pos.getZ() >> 4;
                level.getChunk(lastCx, lastCz);   // generate it, so the save carries it
            }
            level.setBlock(pos, state, 2);
            done[n] = true;
            ++placed;
            --budget;
        }
        if (placed >= set.count) {
            baked = true;
            // The world spawn stays where the void world put it; the camera sync moves the
            // player onto the ball every tick anyway, so there is nothing to gain by moving it.
            server.saveEverything(true, true, true);
            LOGGER.info("voxels: BAKE DONE level {} {} cells in \"{}\"", BAKE_LEVEL, placed,
                    McWorldBootstrap.currentWorldId());
        }
    }

    // ------------------------------------------------------------------ data

    private static double currentCell() {
        final McCameraChannel.Pose pose = McCameraChannel.read();
        return pose != null && pose.unitsPerBlock() > 0.0f ? pose.unitsPerBlock() : 5.48;
    }

    private static int readInt(String name) {
        try {
            final String text = System.getenv(name);
            return text == null || text.isBlank() ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** The baked data for one level, or null when this build does not carry it. */
    public static VoxelSet set(int id) {
        if (id < 1 || id > LEVEL_COUNT) {
            return null;
        }
        if (SETS[id] != null) {
            return SETS[id];
        }
        final String path = String.format("/assets/ballance/voxel/Level_%02d.bin", id);
        try (InputStream raw = LevelVoxels.class.getResourceAsStream(path)) {
            if (raw == null) {
                return null;
            }
            final ByteBuffer buffer = ByteBuffer.wrap(raw.readAllBytes())
                    .order(ByteOrder.LITTLE_ENDIAN);
            final byte[] magic = new byte[8];
            buffer.get(magic);
            if (!new String(magic, java.nio.charset.StandardCharsets.ISO_8859_1)
                    .equals("MPVX2\0\0\0")) {
                LOGGER.warn("voxels: {} is not an MPVX2 file", path);
                return null;
            }
            final int count = buffer.getInt();
            final int walkCount = buffer.getInt();
            final float cell = buffer.getFloat();
            final float minX = buffer.getFloat();
            final float minY = buffer.getFloat();
            final float minZ = buffer.getFloat();
            final int dimI = buffer.getShort() & 0xFFFF;
            final int dimJ = buffer.getShort() & 0xFFFF;
            final int dimK = buffer.getShort() & 0xFFFF;
            final short[] rel = new short[count * 3];
            for (int n = 0; n < count * 3; ++n) {
                rel[n] = (short) buffer.getShort();
            }
            final VoxelSet set = new VoxelSet(id, cell,
                    (int) Math.floor(minX), (int) Math.floor(minY), (int) Math.floor(minZ),
                    dimI, dimJ, dimK, count, walkCount, rel);
            SETS[id] = set;
            return set;
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("voxels: could not read {}", path, error);
            return null;
        }
    }
}
