// shmfeed - synthetic Ballance<->Minecraft frame producer.
//
// Stands in for the Minecraft export mod so the host side of the passthrough
// can be built and measured before any client mod exists. Writes a moving
// BGRA8 pattern plus a 16-bit frame-id barcode into the mailbox defined by
// mpframe.h.
//
//   cc -O2 -o shmfeed shmfeed.c -lrt
//   ./shmfeed --width 320 --height 240 --fps 60

#define _POSIX_C_SOURCE 200809L

#include "mpframe.h"

#include <fcntl.h>
#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t g_Stop = 0;

static void on_signal(int sig) {
    (void)sig;
    g_Stop = 1;
}

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static uint8_t *slot_base(MpFrameHeader *h, uint32_t slot) {
    return (uint8_t *)h + h->SlotOffset[slot];
}

// A pattern where every pixel is a function of (x, y, frame): a chromatic
// background that slides, a white ball on an orbit, a 16-bit barcode of the
// frame id and corner markers so a screenshot shows both content and
// orientation without any OCR.
static void paint(MpFrameHeader *h, uint32_t slot, uint32_t frame) {
    uint8_t *base = slot_base(h, slot);
    const int w = (int)h->Width, ht = (int)h->Height, stride = (int)h->Stride;
    const int ballR = ht / 10 + 2;
    const double angle = (double)frame * 0.06;
    const int cx = w / 2 + (int)((w / 3) * cos(angle));
    const int cy = ht / 2 + (int)((ht / 3) * sin(angle));
    const uint32_t shift = (frame * 3u) & 0xFFu;

    for (int y = 0; y < ht; ++y) {
        uint8_t *row = base + (size_t)y * (size_t)stride;
        for (int x = 0; x < w; ++x) {
            uint8_t *p = row + (size_t)x * 4u;
            const int dx = x - cx, dy = y - cy;
            if (dx * dx + dy * dy <= ballR * ballR) {
                p[0] = p[1] = p[2] = 255;
            } else {
                p[0] = (uint8_t)((x * 255) / (w ? w : 1));         // B
                p[1] = (uint8_t)((y * 255) / (ht ? ht : 1));       // G
                p[2] = (uint8_t)(shift);                            // R
            }
            p[3] = 255;
        }
    }

    // Barcode of the frame id: 16 cells along the bottom, 24 px tall.
    const int cells = 16;
    const int cellW = w / cells;
    const int barH = ht / 10 > 8 ? ht / 10 : 8;
    for (int i = 0; i < cells; ++i) {
        const int on = ((frame >> i) & 1u) != 0;
        for (int y = ht - barH; y < ht; ++y) {
            uint8_t *row = base + (size_t)y * (size_t)stride;
            for (int x = i * cellW; x < (i + 1) * cellW; ++x) {
                uint8_t *p = row + (size_t)x * 4u;
                p[0] = p[1] = p[2] = on ? 255 : 0;
            }
        }
    }

    // Corner markers: white top-left, blue top-right, red bottom-left.
    const int m = w / 32 > 4 ? w / 32 : 4;
    for (int y = 0; y < m; ++y) {
        for (int x = 0; x < m; ++x) {
            uint8_t *tl = base + (size_t)y * (size_t)stride + (size_t)x * 4u;
            tl[0] = tl[1] = tl[2] = 255;
            uint8_t *tr = base + (size_t)y * (size_t)stride + (size_t)(w - 1 - x) * 4u;
            tr[0] = 255; tr[1] = 0; tr[2] = 0;
            uint8_t *bl = base + (size_t)(ht - 1 - y) * (size_t)stride + (size_t)x * 4u;
            bl[0] = 0; bl[1] = 0; bl[2] = 255;
        }
    }
}

int main(int argc, char **argv) {
    int width = 320, height = 240, fps = 60;
    int frozen = 0; // --static: publish one unchanging pattern, for verification
    const char *name = "ballance_mc_frame";

    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--width") && i + 1 < argc) width = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--height") && i + 1 < argc) height = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--fps") && i + 1 < argc) fps = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--name") && i + 1 < argc) name = argv[++i];
        else if (!strcmp(argv[i], "--static")) frozen = 1;
        else {
            fprintf(stderr, "usage: %s [--width N] [--height N] [--fps N] [--name NAME] [--static]\n", argv[0]);
            return 2;
        }
    }
    if (width < 16 || height < 16 || width > 4096 || height > 4096 || fps < 1 || fps > 500) {
        fprintf(stderr, "shmfeed: out of range (width/height 16..4096, fps 1..500)\n");
        return 2;
    }

    char path[128];
    if (name[0] == '/') snprintf(path, sizeof(path), "%s", name);
    else snprintf(path, sizeof(path), "/%s", name);

    const uint32_t stride = (uint32_t)width * 4u;
    const uint32_t slotBytes = stride * (uint32_t)height;
    const size_t total = MPF_HEADER_BYTES + (size_t)slotBytes * MPF_SLOTS;

    const int fd = shm_open(path, O_CREAT | O_RDWR, 0600);
    if (fd < 0) { perror("shm_open"); return 1; }
    if (ftruncate(fd, (off_t)total) != 0) { perror("ftruncate"); return 1; }
    void *map = mmap(NULL, total, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) { perror("mmap"); return 1; }

    MpFrameHeader *h = (MpFrameHeader *)map;
    memset(h, 0, MPF_HEADER_BYTES);
    h->Width = (uint32_t)width;
    h->Height = (uint32_t)height;
    h->Stride = stride;
    h->Format = MPF_FORMAT_BGRA8;
    h->SlotBytes = slotBytes;
    h->SlotCount = MPF_SLOTS;
    h->ProducerPid = (uint32_t)getpid();
    for (uint32_t i = 0; i < MPF_SLOTS; ++i)
        h->SlotOffset[i] = MPF_HEADER_BYTES + (uint64_t)slotBytes * i;
    // Publish the geometry first, the magic last: a consumer that maps while
    // we are still setting up sees Magic == 0 and keeps waiting.
    __atomic_store_n(&h->Version, MPF_VERSION, __ATOMIC_RELEASE);
    __atomic_store_n(&h->Magic, MPF_MAGIC, __ATOMIC_RELEASE);

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);

    const int64_t period = 1000000000LL / fps;
    uint32_t frame = 0;
    int64_t next = now_ns();
    fprintf(stderr, "shmfeed: /dev/shm%s %dx%d @%dfps (%u bytes/slot)\n", path, width, height, fps, slotBytes);

    while (!g_Stop) {
        const uint32_t slot = (frame + 1u) % MPF_SLOTS;
        paint(h, slot, frozen ? 1u : frame + 1u);
        h->TimestampNs = (uint64_t)now_ns();
        __atomic_store_n(&h->PublishedSlot, slot, __ATOMIC_RELEASE);
        frame += 1;
        __atomic_store_n(&h->FrameId, frame, __ATOMIC_RELEASE);

        next += period;
        const int64_t sleep = next - now_ns();
        if (sleep > 0) {
            struct timespec ts = { (time_t)(sleep / 1000000000LL), (long)(sleep % 1000000000LL) };
            nanosleep(&ts, NULL);
        } else if (sleep < -period) {
            next = now_ns();
        }
    }

    fprintf(stderr, "shmfeed: published %u frames, exiting\n", frame);
    return 0;
}
