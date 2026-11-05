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
 * Reader half of the Ballance -&gt; Minecraft camera channel (milestone M1).
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

    public static final int FLAG_VALID = 0x1;
    public static final int FLAG_BALL = 0x2;

    private static final VarHandle SEQ =
            MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());

    /** One coherent sample of the channel. */
    public record Pose(int seq, int flags, int width, int height,
                       double[] eye, double[] forward, double[] up,
                       float fovY, float aspect, float near, float far,
                       double[] ball, float unitsPerBlock, int frame) {
        public boolean valid() {
            return (flags & FLAG_VALID) != 0;
        }

        public boolean hasBall() {
            return (flags & FLAG_BALL) != 0;
        }
    }

    private static MappedByteBuffer map;
    private static boolean disabled;
    private static boolean reported;
    private static long lastAttemptNanos;
    private static String pathLabel = "";

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
            final MappedByteBuffer mapped;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, BYTES);
            }
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            if (mapped.getInt(OFF_MAGIC) != MAGIC) {
                LOGGER.warn("{} is not a camera channel (magic 0x{}), ignoring it",
                        path, Integer.toHexString(mapped.getInt(OFF_MAGIC)));
                return null;
            }
            map = mapped;
            LOGGER.info("camera channel ready: {} ({} bytes)", path, BYTES);
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
            final int after = (int) SEQ.getAcquire(buffer, OFF_SEQ);
            if (before == after) {
                return new Pose(after, flags, width, height, eye, forward, up,
                        fovY, aspect, near, far, ball, unitsPerBlock, frame);
            }
        }
        return null;
    }
}
