package com.example.ballance.passthrough;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Producer side of the Ballance &lt;-&gt; Minecraft frame mailbox (milestone M0b).
 *
 * <p>Every rendered frame this class hands the main render target to Minecraft's own
 * screenshot readback path, converts the resulting RGBA image into the BGRA layout the
 * mailbox specifies and publishes it into a mailbox the Ballance side maps. The layout is
 * documented in {@code tools/passthrough/mpframe.h} of the ballance-mc-passthrough
 * repository and is byte compatible with the synthetic producer (shmfeed.c) used while
 * this module did not exist.
 *
 * <p>The transport differs per platform, only because the OS does. On Linux the mailbox is
 * a file under {@code /dev/shm} (a POSIX shared memory object from the Ballance side). On
 * Windows, which has no shm namespace, it is a memory-mapped file in
 * {@code %LOCALAPPDATA%\BallanceMcPassthrough\}. The consumer resolves the same path
 * independently ({@code sdlgpu-passthrough.cpp}, {@code resolve_path}) and the two halves
 * see each other's writes through the page cache, so nothing else changes.
 *
 * <p>Configuration (environment):
 * <ul>
 *   <li>{@code BALLANCE_MC_SHM}  mailbox name, default {@code ballance_mc_frame};</li>
 *   <li>{@code BALLANCE_MC_MAILBOX} absolute mailbox path, overrides the name;</li>
 *   <li>{@code BALLANCE_MC_FPS}  target publish rate, default 30, 0 = every frame;</li>
 *   <li>{@code BALLANCE_MC_EXPORT} set to {@code 0} to disable the export entirely.</li>
 * </ul>
 */
public final class McFrameExport {
    private static final Logger LOGGER = LoggerFactory.getLogger("ballance-passthrough-frame-export");

    // Keep in sync with tools/passthrough/mpframe.h.
    private static final int MAGIC = 0x3050464D; // 'M''F''P''0' little endian
    private static final int VERSION = 1;
    private static final int FORMAT_BGRA8 = 1;
    private static final int SLOTS = 3;
    private static final int HEADER_BYTES = 4096;

    private static final int OFF_MAGIC = 0;
    private static final int OFF_VERSION = 4;
    private static final int OFF_WIDTH = 8;
    private static final int OFF_HEIGHT = 12;
    private static final int OFF_STRIDE = 16;
    private static final int OFF_FORMAT = 20;
    private static final int OFF_SLOT_BYTES = 24;
    private static final int OFF_SLOT_COUNT = 28;
    private static final int OFF_FRAME_ID = 32;
    private static final int OFF_PUBLISHED_SLOT = 36;
    private static final int OFF_PRODUCER_PID = 40;
    private static final int OFF_FLAGS = 44;
    private static final int OFF_TIMESTAMP = 48;
    private static final int OFF_SLOT_OFFSET = 56;

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();

    private static boolean initialised;
    private static boolean enabled;
    private static long minIntervalNanos;
    private static String mailboxPath;

    private static MappedByteBuffer map;
    private static IntBuffer mapInts;
    private static int width;
    private static int height;
    private static int frameId;
    private static int nextSlot;

    private static long lastRequestNanos;
    private static int published;
    private static int skipped;
    private static int failures;
    private static long statsSinceNanos;

    private McFrameExport() {
    }

    public static void initialise() {
        if (initialised) {
            return;
        }
        initialised = true;
        String value = System.getenv("BALLANCE_MC_EXPORT");
        enabled = value == null || value.isEmpty() || !(value.equals("0") || value.equalsIgnoreCase("off")
                || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("no"));
        String name = System.getenv("BALLANCE_MC_SHM");
        if (name == null || name.isEmpty()) {
            name = "ballance_mc_frame";
        }
        mailboxPath = resolveMailboxPath(name);
        int fps = 30;
        String fpsValue = System.getenv("BALLANCE_MC_FPS");
        if (fpsValue != null && !fpsValue.isEmpty()) {
            try {
                fps = Integer.parseInt(fpsValue.trim());
            } catch (NumberFormatException ignored) {
                fps = 30;
            }
        }
        minIntervalNanos = fps > 0 ? 1_000_000_000L / fps : 0L;
        statsSinceNanos = System.nanoTime();
        LOGGER.info("frame export {} (mailbox {}, target {} fps)", enabled ? "enabled" : "disabled",
                mailboxPath, fps > 0 ? fps : "every");
    }

    /**
     * Resolves the mailbox path exactly the way the Ballance-side consumer does
     * ({@code resolve_path} in sdlgpu-passthrough.cpp). {@code BALLANCE_MC_MAILBOX} wins;
     * otherwise the name is a {@code /dev/shm} object on Linux and a file under
     * {@code %LOCALAPPDATA%\BallanceMcPassthrough} on Windows, which has no shm namespace.
     */
    private static String resolveMailboxPath(String name) {
        String explicitPath = System.getenv("BALLANCE_MC_MAILBOX");
        if (explicitPath != null && !explicitPath.isEmpty()) {
            return explicitPath;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String base = System.getenv("LOCALAPPDATA");
            if (base == null || base.isEmpty()) {
                base = System.getenv("TEMP");
            }
            if (base == null || base.isEmpty()) {
                base = System.getProperty("java.io.tmpdir");
            }
            return new File(new File(base, "BallanceMcPassthrough"), name + ".dat").getAbsolutePath();
        }
        return "/dev/shm" + (name.startsWith("/") ? name : "/" + name);
    }

    /**
     * Called once per rendered frame, from the render thread, after the frame has been
     * fully drawn. Schedules a readback of the main render target if the rate limit and
     * the previous readback allow it.
     */
    public static void onFrameRendered(Minecraft minecraft) {
        if (!initialised) {
            initialise();
        }
        if (!enabled || minecraft == null) {
            return;
        }
        long now = System.nanoTime();
        if (minIntervalNanos > 0 && now - lastRequestNanos < minIntervalNanos) {
            ++skipped;
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            ++skipped;
            return;
        }
        lastRequestNanos = now;
        RenderTarget target;
        try {
            target = minecraft.gameRenderer.mainRenderTarget();
        } catch (Throwable throwable) {
            IN_FLIGHT.set(false);
            noteFailure(throwable);
            return;
        }
        if (target == null || target.getColorTexture() == null || target.width <= 0 || target.height <= 0) {
            IN_FLIGHT.set(false);
            return;
        }
        try {
            Screenshot.takeScreenshot(target, 1, McFrameExport::publish);
        } catch (Throwable throwable) {
            IN_FLIGHT.set(false);
            noteFailure(throwable);
        }
    }

    private static void publish(NativeImage image) {
        try (NativeImage frame = image) {
            int frameWidth = frame.getWidth();
            int frameHeight = frame.getHeight();
            if (frameWidth < 8 || frameHeight < 8) {
                return;
            }
            if (!ensureMailbox(frameWidth, frameHeight)) {
                return;
            }
            ByteBuffer source = frame.getPixelBytes().order(ByteOrder.LITTLE_ENDIAN);
            IntBuffer pixels = source.asIntBuffer();
            int stride = frameWidth * 4;
            int slot = nextSlot;
            nextSlot = (nextSlot + 1) % SLOTS;
            int target = (HEADER_BYTES + slot * stride * frameHeight) / 4;
            int count = frameWidth * frameHeight;
            // NativeImage memory is R,G,B,A; the mailbox wants B,G,R,A. Both views are
            // little endian so one integer holds A<<24 | B<<16 | G<<8 | R and the swap is
            // a pair of shifts on the red and blue bytes.
            for (int i = 0; i < count; i++) {
                int argb = pixels.get(i);
                mapInts.put(target + i, (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >>> 16) & 0xFF));
            }
            VarHandle.storeStoreFence();
            map.putInt(OFF_PUBLISHED_SLOT, slot);
            VarHandle.storeStoreFence();
            map.putInt(OFF_FRAME_ID, ++frameId);
            ++published;
            logStats();
        } catch (Throwable throwable) {
            noteFailure(throwable);
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    private static boolean ensureMailbox(int frameWidth, int frameHeight) {
        if (map != null && width == frameWidth && height == frameHeight) {
            return true;
        }
        if (map != null) {
            // The consumer maps the object once; a size change invalidates that mapping.
            // M0 keeps the first published size, which is the window size of the run.
            LOGGER.warn("frame size changed from {}x{} to {}x{}; mailbox keeps the old size",
                    width, height, frameWidth, frameHeight);
            return false;
        }
        long slotBytes = (long) frameWidth * 4L * frameHeight;
        long total = HEADER_BYTES + slotBytes * SLOTS;
        try {
            File file = new File(mailboxPath);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("cannot create mailbox directory " + parent);
            }
            if (file.exists() && !file.delete()) {
                LOGGER.warn("cannot replace stale mailbox {}", mailboxPath);
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(total);
                FileChannel channel = raf.getChannel();
                MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, total);
                mapped.order(ByteOrder.LITTLE_ENDIAN);
                map = mapped;
                mapInts = mapped.asIntBuffer();
            }
            map.putInt(OFF_MAGIC, MAGIC);
            map.putInt(OFF_VERSION, VERSION);
            map.putInt(OFF_WIDTH, frameWidth);
            map.putInt(OFF_HEIGHT, frameHeight);
            map.putInt(OFF_STRIDE, frameWidth * 4);
            map.putInt(OFF_FORMAT, FORMAT_BGRA8);
            map.putInt(OFF_SLOT_BYTES, (int) slotBytes);
            map.putInt(OFF_SLOT_COUNT, SLOTS);
            map.putInt(OFF_FRAME_ID, 0);
            map.putInt(OFF_PUBLISHED_SLOT, 0);
            map.putInt(OFF_PRODUCER_PID, (int) ProcessHandle.current().pid());
            map.putInt(OFF_FLAGS, 0);
            map.putLong(OFF_TIMESTAMP, System.nanoTime());
            for (int i = 0; i < SLOTS; i++) {
                map.putLong(OFF_SLOT_OFFSET + i * 8, HEADER_BYTES + slotBytes * i);
            }
            width = frameWidth;
            height = frameHeight;
            frameId = 0;
            nextSlot = 0;
            LOGGER.info("mailbox {} ready, {}x{} BGRA8, {} slots, {} bytes", mailboxPath, frameWidth,
                    frameHeight, SLOTS, total);
            return true;
        } catch (Throwable throwable) {
            noteFailure(throwable);
            return false;
        }
    }

    private static void logStats() {
        long now = System.nanoTime();
        if (now - statsSinceNanos < 5_000_000_000L) {
            return;
        }
        long seconds = Math.max(1L, (now - statsSinceNanos) / 1_000_000_000L);
        LOGGER.info("published {} frames in {}s (~{} fps), skipped {}, failures {}",
                published, seconds, published / seconds, skipped, failures);
        statsSinceNanos = now;
        published = 0;
        skipped = 0;
        failures = 0;
    }

    private static void noteFailure(Throwable throwable) {
        if (++failures <= 3) {
            LOGGER.warn("frame export failed", throwable);
        }
    }
}
