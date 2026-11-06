package com.example.ballance.passthrough;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
 *   <li>{@code BALLANCE_MC_EXPORT} set to {@code 0} to disable the export entirely;</li>
 *   <li>{@code BALLANCE_MC_STAGE}  narrow the pipeline for A/B runs, see {@link #stage};</li>
 *   <li>{@code BALLANCE_MC_TRACE}  {@code 1} (or a path) to write a flushed breadcrumb
 *       log, so a native fault still leaves the last completed step behind.</li>
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

    /**
     * 透明度统计：这一段时间里有多少像素的 alpha 是 0。这是「背景透明」这件事唯一
     * 客观的数字——虚空世界里没有人放方块时它应该接近 100%，有方块时才掉下来。
     */
    private static long backgroundZeros;
    private static long backgroundSamples;

    /**
     * How much of the pipeline to run. Production is {@link #STAGE_FULL}; the other
     * values exist only so an A/B run can say which half of it the host dislikes:
     * <ol start="0">
     *   <li>screenshot readback only -- the image is closed, nothing touches the mailbox;</li>
     *   <li>mailbox header and frame counters only -- no pixel copy at all;</li>
     *   <li>full pipeline, pixels copied into the mapping (the production path);</li>
     *   <li>pixels copied into a plain heap array instead of the mapping.</li>
     * </ol>
     */
    private static final int STAGE_SCREENSHOT = 1;
    private static final int STAGE_HEADER = 2;
    private static final int STAGE_FULL = 3;
    private static final int STAGE_HEAP = 4;

    private static int stage = STAGE_FULL;

    private static PrintStream traceStream;
    private static int traceBudget = 400;
    private static int[] heapScratch;

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
        stage = readInt(System.getenv("BALLANCE_MC_STAGE"), STAGE_FULL);
        openTrace();
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
        LOGGER.info("frame export {} (mailbox {}, target {} fps, stage {})", enabled ? "enabled" : "disabled",
                mailboxPath, fps > 0 ? fps : "every", stage);
        trace("initialise enabled=" + enabled + " stage=" + stage + " mailbox=" + mailboxPath);
    }

    private static int readInt(String text, int fallback) {
        if (text == null || text.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Opens the breadcrumb log. {@code BALLANCE_MC_TRACE=1} writes next to the mailbox
     * ({@code ballance_mc_frame-trace.log}); any other value is taken as the file name.
     * Every line is flushed, because the point of the file is to survive a hard fault.
     */
    private static void openTrace() {
        String value = System.getenv("BALLANCE_MC_TRACE");
        if (value == null || value.isEmpty() || value.equals("0")) {
            return;
        }
        String path = value.equals("1")
                ? mailboxPath.replaceAll("\\.dat$", "") + "-trace.log"
                : value;
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            traceStream = new PrintStream(new FileOutputStream(file, false), true, StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            traceStream = null;
            LOGGER.warn("cannot open trace file {}", path, throwable);
        }
    }

    /** Thread and stack depth, so a native fault can be told apart from deep recursion. */
    private static String where() {
        if (traceStream == null) {
            return "";
        }
        return " thread=" + Thread.currentThread().getName()
                + " depth=" + new Throwable().getStackTrace().length;
    }

    private static void trace(String message) {
        PrintStream out = traceStream;
        if (out == null || traceBudget <= 0) {
            return;
        }
        --traceBudget;
        out.println((System.nanoTime() / 1_000_000L) + " " + message);
        out.flush();
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
        trace("request " + target.width + "x" + target.height + where());
        try {
            capture(target);
            trace("request handed to the readback");
        } catch (Throwable throwable) {
            IN_FLIGHT.set(false);
            noteFailure(throwable);
        }
    }

    /**
     * 把主渲染目标读回内存。
     *
     * <p><b>不用 {@code Screenshot.takeScreenshot}</b>：它的最后一步是
     * {@code image.setPixelABGR(x, y, argb | 0xFF000000)}——alpha 被写死成 255。
     * 那是给人看的截图，对我们正好把唯一重要的通道抹掉（2026-11-06 实测：帧里
     * 100% 的像素 a=255，而清屏色明明是 alpha 0）。这里照抄它的读回流程，
     * 只去掉那个或运算。
     */
    private static void capture(RenderTarget target) {
        final GpuTexture source = target.getColorTexture();
        final int frameWidth = target.width;
        final int frameHeight = target.height;
        final int blockSize = source.getFormat().blockSize();
        final GpuBuffer buffer = RenderSystem.getDevice()
                .createBuffer(() -> "ballance-passthrough-frame", 9, (long) frameWidth * frameHeight * blockSize);
        RenderSystem.getDevice()
                .createCommandEncoder()
                .copyTextureToBuffer(source, buffer, 0L, () -> {
                    try (MappedView read = buffer.map(true, false)) {
                        publishRows(read.data(), frameWidth, frameHeight, blockSize);
                    } catch (Throwable throwable) {
                        noteFailure(throwable);
                    } finally {
                        buffer.close();
                        IN_FLIGHT.set(false);
                    }
                }, 0);
    }

    /**
     * 把读回的一帧写进信箱。{@code data} 是纹理原样的行优先字节（每像素
     * {@code blockSize} 字节、R,G,B,A 顺序、**第一行是画面最下面一行**——GL 的约定），
     * 所以这里同时做上下翻转和 R/B 交换，alpha 原样保留。
     */
    private static void publishRows(ByteBuffer data, int frameWidth, int frameHeight, int blockSize) {
        trace("publish enter" + where());
        try {
            trace("publish image " + frameWidth + "x" + frameHeight);
            if (frameWidth < 8 || frameHeight < 8) {
                return;
            }
            if (stage == STAGE_SCREENSHOT) {
                ++published;
                logStats();
                trace("publish stage1 done");
                return;
            }
            if (!ensureMailbox(frameWidth, frameHeight)) {
                trace("publish no mailbox");
                return;
            }
            int slot = nextSlot;
            nextSlot = (nextSlot + 1) % SLOTS;
            int stride = frameWidth * 4;
            int target = (HEADER_BYTES + slot * stride * frameHeight) / 4;
            int count = frameWidth * frameHeight;
            if (stage == STAGE_HEADER) {
                VarHandle.storeStoreFence();
                map.putInt(OFF_PUBLISHED_SLOT, slot);
                VarHandle.storeStoreFence();
                map.putInt(OFF_FRAME_ID, ++frameId);
                ++published;
                logStats();
                trace("publish stage2 done slot=" + slot);
                return;
            }
            final ByteBuffer source = data.order(ByteOrder.LITTLE_ENDIAN);
            trace("publish bytes cap=" + source.capacity() + " pixels=" + count + " blockSize=" + blockSize);
            if (stage == STAGE_HEAP) {
                if (heapScratch == null || heapScratch.length != count) {
                    heapScratch = new int[count];
                }
                copy(source, frameWidth, frameHeight, blockSize, heapScratch, 0, null);
                trace("publish stage4 heap copy done");
            } else {
                copy(source, frameWidth, frameHeight, blockSize, null, target, mapInts);
            }
            VarHandle.storeStoreFence();
            trace("publish fence");
            map.putInt(OFF_PUBLISHED_SLOT, slot);
            VarHandle.storeStoreFence();
            map.putInt(OFF_FRAME_ID, ++frameId);
            ++published;
            trace("publish done slot=" + slot + " frameId=" + frameId);
            logStats();
        } catch (Throwable throwable) {
            noteFailure(throwable);
        } finally {
            trace("publish exit");
            IN_FLIGHT.set(false);
        }
    }

    /**
     * 翻转 + 换通道 + 数 alpha，按行推进，好让面包屑日志能说出跑到第几行。
     *
     * <p>纹理里一个像素是 R,G,B,A 四个字节；信箱要 B,G,R,A。小端读成一个 int 就是
     * A&lt;&lt;24 | B&lt;&lt;16 | G&lt;&lt;8 | R，交换只是 R 和 B 两个字节的位移。
     * <b>alpha 一律原样搬运</b>——它就是「背景透不透明」的全部信息。
     */
    private static void copy(ByteBuffer source, int frameWidth, int frameHeight, int blockSize,
                             int[] heap, int mailboxBase, IntBuffer mailbox) {
        final int count = frameWidth * frameHeight;
        final int rowsPerStep = Math.max(1, frameHeight / 8);
        long zeros = 0;
        for (int rowBase = 0; rowBase < frameHeight; rowBase += rowsPerStep) {
            int rowEnd = Math.min(frameHeight, rowBase + rowsPerStep);
            for (int y = rowBase; y < rowEnd; y++) {
                // 输出第 y 行（自上而下）来自纹理第 (frameHeight - 1 - y) 行（自下而上）
                int sourceRow = frameHeight - 1 - y;
                int sourceBase = sourceRow * frameWidth * blockSize;
                int targetBase = y * frameWidth;
                for (int x = 0; x < frameWidth; x++) {
                    int argb = source.getInt(sourceBase + x * blockSize);
                    if ((argb >>> 24) == 0) {
                        ++zeros;
                    }
                    int bgra = (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >>> 16) & 0xFF);
                    int i = targetBase + x;
                    if (mailbox != null) {
                        mailbox.put(mailboxBase + i, bgra);
                    } else {
                        heap[i] = bgra;
                    }
                }
            }
            trace("copied rows " + rowEnd + "/" + frameHeight);
        }
        backgroundZeros += zeros;
        backgroundSamples += count;
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
            trace("mapped " + total + " bytes, ints=" + mapInts.capacity());
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
            trace("header written");
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
        String background = backgroundSamples > 0
                ? String.format(Locale.ROOT, "%.1f%%", 100.0 * backgroundZeros / backgroundSamples)
                : "n/a";
        LOGGER.info("published {} frames in {}s (~{} fps), skipped {}, failures {}, transparent {}",
                published, seconds, published / seconds, skipped, failures, background);
        statsSinceNanos = now;
        published = 0;
        skipped = 0;
        failures = 0;
        backgroundZeros = 0;
        backgroundSamples = 0;
    }

    private static void noteFailure(Throwable throwable) {
        if (++failures <= 3) {
            LOGGER.warn("frame export failed", throwable);
            trace("failure " + throwable);
        }
    }
}
