package com.example.ballance.passthrough;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Ballance &lt;-&gt; Minecraft camera channel (milestone M1, extended by M1b).
 *
 * <p>The Ballance side (Source/Player/src/MinecraftCameraSync.cpp) publishes the viewpoint the
 * scene was just rendered from, plus the player ball position, into a 4 KiB shared object. This
 * class maps it and hands out a coherent snapshot; {@link CameraSync} applies it and
 * {@code CameraMixin} installs it into {@code net.minecraft.client.Camera}.
 *
 * <p>The payload is a seqlock: the sequence number at offset 8 is bumped with release semantics
 * after the fields are written, so a sample is accepted only when it reads the same before and
 * after. Nothing here blocks; a writer that is mid-publish just costs a retry.
 *
 * <p>M1b added two things to the same object. The first is the aim lift plus the input mirror
 * (pointer, buttons, wheel, the E and Escape presses) inside the camera payload, so a sample
 * carries a camera and the pointer that belong to the same frame. The second is a small status
 * block at {@link #OFF_STATUS}, written by this side: Ballance has to know whether the inventory
 * it opened is still on screen, because that is what freezes the level there. It has its own
 * sequence number, because the writer is the other process.
 *
 * <p>M1c (2026-11-08) added the game's own level number to the same payload
 * ({@link #OFF_LEVEL_ID}). It rides the seqlock with everything else, so a sample never
 * pairs this frame's level with last frame's ball position.
 *
 * <p>Offsets mirror MpCamera.h. A change there has to be repeated here and in
 * tools/passthrough/inspect-camera.py.
 */
public final class McCameraChannel {
    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-camera");

    private static final int BYTES = 4096;
    private static final int MAGIC = 0x3143504D; // 'M''P''C''1'
    private static final int OFF_MAGIC = 0;
    private static final int OFF_VERSION = 4;
    private static final int OFF_SEQ = 8;
    private static final int OFF_FLAGS = 12;
    private static final int OFF_WIDTH = 16;
    private static final int OFF_HEIGHT = 20;
    private static final int OFF_EYE = 24;
    private static final int OFF_FORWARD = 36;
    private static final int OFF_UP = 48;
    private static final int OFF_FOV_Y = 60;
    private static final int OFF_ASPECT = 64;
    private static final int OFF_NEAR = 68;
    private static final int OFF_FAR = 72;
    private static final int OFF_BALL = 76;
    private static final int OFF_UNITS_PER_BLOCK = 88;
    private static final int OFF_FRAME = 92;
    private static final int OFF_TIME_NS = 96;
    // --- M1b: these took the place of version 1's Reserved[0..], so every
    // offset above is unchanged and an older reader just ignores them.
    private static final int OFF_AIM_LIFT = 104;
    private static final int OFF_MOUSE_X = 108;
    private static final int OFF_MOUSE_Y = 112;
    private static final int OFF_BUTTONS = 116;
    private static final int OFF_WHEEL = 120;
    private static final int OFF_KEY_E = 124;
    private static final int OFF_KEY_ESC = 128;
    // --- M1c: which level the game loaded, from the number in its file name
    // ("...\Level_03.nmo"); 0 = no level loaded yet, which is every menu frame and the
    // frames around a level start.
    private static final int OFF_LEVEL_ID = 132;

    // The status block this side writes (MPS_OFFSET in MpCamera.h).
    private static final int OFF_STATUS = 2048;
    private static final int OFF_STATUS_SEQ = OFF_STATUS + 4;
    private static final int OFF_STATUS_GUI_OPEN = OFF_STATUS + 8;
    private static final int OFF_STATUS_TICKS = OFF_STATUS + 12;
    private static final int OFF_STATUS_KEY_E = OFF_STATUS + 16;
    private static final int OFF_STATUS_KEY_ESC = OFF_STATUS + 20;
    private static final int OFF_STATUS_GUI_WIDTH = OFF_STATUS + 24;
    private static final int OFF_STATUS_GUI_HEIGHT = OFF_STATUS + 28;
    private static final int STATUS_MAGIC = 0x3153504D; // 'M''P''S''1'

    public static final int FLAG_VALID = 0x1;
    public static final int FLAG_BALL = 0x2;
    /**
     * A level is running (2026-11-07). The menu also has ball-shaped objects parked at the
     * origin, so {@link #FLAG_BALL} alone cannot tell "in a level" from "in a menu"; this flag
     * is set from the attached camera instead (see MpCamera.h).
     */
    public static final int FLAG_LEVEL = 0x8;

    public static final int BUTTON_LEFT = 0x1;
    public static final int BUTTON_RIGHT = 0x2;
    public static final int BUTTON_MIDDLE = 0x4;

    /** Windows wheel units per notch, which is what the Ballance side counts in. */
    public static final double WHEEL_UNITS_PER_NOTCH = 120.0;

    private static final VarHandle SEQ =
            MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle STATUS_SEQ =
            MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());

    /**
     * One coherent sample of the channel.
     *
     * <p>{@code levelId} is the number in the level file Ballance loaded. 0 means no level has
     * been loaded yet (menus, and the frames around a level start). It is the only reliable way
     * to name the level: working it out from where the ball stands ties on the five starts that
     * share a floor (3, 5, 7, 9, 11).
     */
    public record Pose(int seq, int flags, int width, int height,
                       double[] eye, double[] forward, double[] up,
                       float fovY, float aspect, float near, float far,
                       double[] ball, float unitsPerBlock, int frame,
                       float aimLift, float mouseX, float mouseY, int buttons, int wheel,
                       int keyE, int keyEsc, int levelId) {
        public boolean valid() {
            return (flags & FLAG_VALID) != 0;
        }

        public boolean hasBall() {
            return (flags & FLAG_BALL) != 0;
        }

        /** True while a level is running; false in the menus. */
        public boolean inLevel() {
            return (flags & FLAG_LEVEL) != 0;
        }


    }

    private static MappedByteBuffer map;
    private static boolean writable;
    private static boolean disabled;
    private static boolean reported;
    private static boolean reportedStatus;
    private static long lastAttemptNanos;
    private static String pathLabel = "";
    private static int statusSeq;

    private McCameraChannel() {
    }

    /** The mapping, or null when Ballance has not published anything yet. */
    private static MappedByteBuffer mapping() {
        if (disabled) {
            return null;
        }
        MappedByteBuffer current = map;
        if (current != null) {
            return current;
        }
        final long now = System.nanoTime();
        if (lastAttemptNanos != 0L && now - lastAttemptNanos < 2_000_000_000L) {
            return null;
        }
        lastAttemptNanos = now;

        final String path = resolvePath();
        pathLabel = path;
        try {
            final Path file = Paths.get(path);
            if (!Files.exists(file)) {
                if (!reported) {
                    reported = true;
                    LOGGER.info("waiting for the Ballance camera channel at {}", path);
                }
                return null;
            }
            MappedByteBuffer mapped = null;
            // Read-write first: the status block goes back the other way. A file
            // that cannot be written (a read-only mount, a copy of one) still
            // drives the camera; only the inventory keys stop working.
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                    StandardOpenOption.WRITE)) {
                mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, BYTES);
                writable = true;
            } catch (IOException readWriteFailed) {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                    mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, BYTES);
                    writable = false;
                }
                LOGGER.warn("camera channel at {} is read-only ({}); the inventory key cannot "
                        + "report back", path, readWriteFailed.toString());
            }
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            if (mapped.getInt(OFF_MAGIC) != MAGIC) {
                LOGGER.warn("{} is not a camera channel (magic 0x{}), ignoring it",
                        path, Integer.toHexString(mapped.getInt(OFF_MAGIC)));
                return null;
            }
            map = mapped;
            LOGGER.info("camera channel ready: {} ({} bytes, {})", path, BYTES,
                    writable ? "read-write" : "read-only");
            return mapped;
        } catch (IOException e) {
            LOGGER.warn("cannot map the camera channel at {}: {}", path, e.toString());
            return null;
        }
    }

    private static String resolvePath() {
        final String explicit = System.getenv("BALLANCE_MC_CAMERA_PATH");
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String name = System.getenv("BALLANCE_MC_CAMERA_SHM");
        if (name == null || name.isEmpty()) {
            name = "ballance_mc_camera";
        }
        final String local = System.getenv("LOCALAPPDATA");
        if (System.getProperty("os.name", "").toLowerCase().contains("win") && local != null) {
            return Paths.get(local, "BallanceMcPassthrough", name + ".dat").toString();
        }
        return Paths.get("/dev/shm", name).toString();
    }

    public static String label() {
        return pathLabel;
    }

    /** Samples the channel; returns null when it is missing, unpublished or torn. */
    public static Pose read() {
        final MappedByteBuffer buffer = mapping();
        if (buffer == null) {
            return null;
        }
        for (int attempt = 0; attempt < 64; attempt++) {
            final int before = (int) SEQ.getAcquire(buffer, OFF_SEQ);
            if (before == 0) {
                return null; // nothing published since the writer created it
            }
            final int flags = buffer.getInt(OFF_FLAGS);
            final int width = buffer.getInt(OFF_WIDTH);
            final int height = buffer.getInt(OFF_HEIGHT);
            final double[] eye = new double[3];
            final double[] forward = new double[3];
            final double[] up = new double[3];
            final double[] ball = new double[3];
            for (int i = 0; i < 3; i++) {
                eye[i] = buffer.getFloat(OFF_EYE + i * 4);
                forward[i] = buffer.getFloat(OFF_FORWARD + i * 4);
                up[i] = buffer.getFloat(OFF_UP + i * 4);
                ball[i] = buffer.getFloat(OFF_BALL + i * 4);
            }
            final float fovY = buffer.getFloat(OFF_FOV_Y);
            final float aspect = buffer.getFloat(OFF_ASPECT);
            final float near = buffer.getFloat(OFF_NEAR);
            final float far = buffer.getFloat(OFF_FAR);
            final float unitsPerBlock = buffer.getFloat(OFF_UNITS_PER_BLOCK);
            final int frame = buffer.getInt(OFF_FRAME);
            final float aimLift = buffer.getFloat(OFF_AIM_LIFT);
            final float mouseX = buffer.getFloat(OFF_MOUSE_X);
            final float mouseY = buffer.getFloat(OFF_MOUSE_Y);
            final int buttons = buffer.getInt(OFF_BUTTONS);
            final int wheel = buffer.getInt(OFF_WHEEL);
            final int keyE = buffer.getInt(OFF_KEY_E);
            final int keyEsc = buffer.getInt(OFF_KEY_ESC);
            final int levelId = buffer.getInt(OFF_LEVEL_ID);
            final int after = (int) SEQ.getAcquire(buffer, OFF_SEQ);
            if (before == after) {
                return new Pose(after, flags, width, height, eye, forward, up,
                        fovY, aspect, near, far, ball, unitsPerBlock, frame,
                        aimLift, mouseX, mouseY, buttons, wheel, keyE, keyEsc, levelId);
            }
        }
        return null;
    }

    /**
     * Publishes what this client is doing with the pointer, for the Ballance side.
     *
     * <p>Only {@code guiOpen} matters to it -- that is the flag that freezes the level -- but
     * the rest is written too, because "the client is alive and has consumed key press N" is
     * what turns a silent desync into a readable log line.
     */
    public static void writeStatus(boolean guiOpen, int guiWidth, int guiHeight,
                                   int keyE, int keyEsc, int ticks) {
        final MappedByteBuffer buffer = map;
        if (buffer == null || !writable) {
            return;
        }
        final int seq = ++statusSeq;
        buffer.putInt(OFF_STATUS, STATUS_MAGIC);
        buffer.putInt(OFF_STATUS_GUI_OPEN, guiOpen ? 1 : 0);
        buffer.putInt(OFF_STATUS_TICKS, ticks);
        buffer.putInt(OFF_STATUS_KEY_E, keyE);
        buffer.putInt(OFF_STATUS_KEY_ESC, keyEsc);
        buffer.putInt(OFF_STATUS_GUI_WIDTH, guiWidth);
        buffer.putInt(OFF_STATUS_GUI_HEIGHT, guiHeight);
        // Release: everything above has to be visible to a reader that acquires seq.
        STATUS_SEQ.setRelease(buffer, OFF_STATUS_SEQ, seq);
        if (!reportedStatus) {
            reportedStatus = true;
            LOGGER.info("status channel ready: guiOpen={} at offset {}", guiOpen ? 1 : 0, OFF_STATUS);
        }
    }
}
