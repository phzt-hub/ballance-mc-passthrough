// Host side of the Ballance <-> Minecraft passthrough (milestone M0).
//
// Reads the frame mailbox described by mpframe.h and draws the newest frame as
// a fullscreen quad over the resolved scene, before the game's 2D interface is
// drawn and before the frame is presented. Nothing here knows about geometry,
// cameras or depth yet: M0 only proves the transport plus one textured quad at
// an acceptable cost.

#include "CKSdlGpuPassthrough.h"

#include "CKSdlGpuRasterizerContext.h"
#include "mpframe.h"

#include <fcntl.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <cstdarg>
#include <cstdio>
#include <cstdlib>

namespace {

constexpr uint32_t kMinExtent = 8;
constexpr uint32_t kMaxExtent = 8192;
// The mailbox is retried at this cadence while the producer is absent, and the
// statistics line is printed at this cadence once it is present.
constexpr uint32_t kOpenRetryTicks = 30;
constexpr int64_t kStatsIntervalMs = 2000;

int64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void log_line(const char *fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    fprintf(stderr, "[mc-passthrough] %s\n", buffer);
    fflush(stderr);
}

CKBOOL env_flag(const char *name) {
    const char *value = getenv(name);
    if (!value || !*value) return FALSE;
    if (!strcmp(value, "0") || !strcmp(value, "off") || !strcmp(value, "false") ||
        !strcmp(value, "no")) return FALSE;
    return TRUE;
}

void initialise(CKSdlGpuPassthroughState &state) {
    state.Initialised = TRUE;
    state.Enabled = env_flag("BALLANCE_MC_PASSTHROUGH");
    const char *name = getenv("BALLANCE_MC_SHM");
    if (!name || !*name) name = "ballance_mc_frame";
    if (*name == '/')
        snprintf(state.Path, sizeof(state.Path), "%s", name);
    else
        snprintf(state.Path, sizeof(state.Path), "/%s", name);
    state.StartMs = now_ms();
    if (state.Enabled)
        log_line("enabled, mailbox %s", state.Path);
}

// Maps the mailbox if the producer has created it. Cheap enough to retry, but
// only every kOpenRetryTicks ticks so a missing producer costs nothing.
CKBOOL try_map(CKSdlGpuPassthroughState &state) {
    if (state.Mapped) return TRUE;
    if (state.OpenAttempts > 0 && (state.Ticks % kOpenRetryTicks) != 0) return FALSE;
    ++state.OpenAttempts;

    const int fd = shm_open(state.Path, O_RDONLY, 0);
    if (fd < 0) {
        if (!state.ReportedMissing) {
            state.ReportedMissing = TRUE;
            log_line("waiting for %s (producer not running yet)", state.Path);
        }
        return FALSE;
    }
    struct stat info;
    if (fstat(fd, &info) != 0 || (size_t)info.st_size < MPF_HEADER_BYTES) {
        close(fd);
        if (!state.ReportedBadHeader) {
            state.ReportedBadHeader = TRUE;
            log_line("%s is smaller than the header (%lld bytes)", state.Path,
                     (long long)info.st_size);
        }
        return FALSE;
    }
    void *base = mmap(nullptr, (size_t)info.st_size, PROT_READ, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) {
        close(fd);
        return FALSE;
    }
    state.Fd = fd;
    state.Base = base;
    state.Bytes = (size_t)info.st_size;
    state.Mapped = TRUE;
    state.ReportedMissing = FALSE;
    log_line("mapped %s (%zu bytes)", state.Path, state.Bytes);
    return TRUE;
}

void destroy_texture(CKSdlGpuRasterizerContext &context,
                     CKSdlGpuPassthroughState &state) {
    if (state.Texture) {
        context.DeleteObject(state.Texture, CKRST_OBJ_TEXTURE);
        state.Texture = 0;
        state.TextureWidth = 0;
        state.TextureHeight = 0;
    }
}

// Creates the sampling texture for the published frame size and keeps its
// pixels undefined: every consumed frame uploads a whole level.
CKBOOL ensure_texture(CKSdlGpuRasterizerContext &context,
                      CKSdlGpuPassthroughState &state, uint32_t width,
                      uint32_t height) {
    if (state.Texture && state.TextureWidth == width && state.TextureHeight == height)
        return TRUE;
    destroy_texture(context, state);
    CKTextureDesc desc;
    VxPixelFormat2ImageDesc(_32_ARGB8888, desc.Format);
    desc.Format.Width = (int)width;
    desc.Format.Height = (int)height;
    desc.Format.BytesPerLine = (int)(width * 4u);
    desc.Flags = CKRST_TEXTURE_RGB | CKRST_TEXTURE_ALPHA;
    desc.MipMapCount = 1;
    desc.Depth = 1;
    CKDWORD texture = 0;
    if (!context.CreateTexture(&desc, &texture) || !texture) {
        log_line("CreateTexture %ux%u failed", width, height);
        return FALSE;
    }
    state.Texture = texture;
    state.TextureWidth = width;
    state.TextureHeight = height;
    log_line("texture for %ux%u frame (handle %u)", width, height, (unsigned)texture);
    return TRUE;
}

void log_stats(CKSdlGpuPassthroughState &state) {
    const int64_t now = now_ms();
    if (state.LastLogMs != 0 && now - state.LastLogMs < kStatsIntervalMs) return;
    const int64_t span = now - (state.LastLogMs == 0 ? state.StartMs : state.LastLogMs);
    // The counters are cumulative while span covers this interval only, so the rate has
    // to come from the delta since the previous line, not from the running total.
    const uint64_t consumed = state.Frames - state.FramesAtLastLog;
    state.FramesAtLastLog = state.Frames;
    state.LastLogMs = now;
    if (span <= 0) return;
    log_line("frames=%llu redraws=%llu draws=%llu misses=%llu rejected=%llu (%llu fps consumed)",
             (unsigned long long)state.Frames, (unsigned long long)state.Redraws,
             (unsigned long long)state.Draws, (unsigned long long)state.Misses,
             (unsigned long long)state.Rejected,
             (unsigned long long)(consumed * 1000 / (uint64_t)span));
}

} // namespace

CKBOOL CKSdlGpuPassthroughTick(CKSdlGpuRasterizerContext &Context,
                               CKSdlGpuPassthroughState &State) {
    if (!State.Initialised) initialise(State);
    if (!State.Enabled) return FALSE;
    ++State.Ticks;
    if (!try_map(State)) return FALSE;

    const MpFrameHeader *header = static_cast<const MpFrameHeader *>(State.Base);
    const uint32_t frameId = __atomic_load_n(&header->FrameId, __ATOMIC_ACQUIRE);
    if (frameId == 0 || frameId == State.LastFrameId) {
        if (!State.HasContent || !State.Texture) {
            ++State.Misses;
            log_stats(State);
            return FALSE;
        }
        // The producer is usually SLOWER than this renderer (a software-rendered
        // Minecraft manages 14-26 fps against ~54 fps here). Drawing only on the
        // ticks that carry fresh pixels would strobe: a quarter of the presented
        // frames would show the overlay and the rest the game's own scene. So the
        // last uploaded texture is re-presented on every tick that has no new data.
        ++State.Redraws;
        const CKERROR again = Context.m_Present.SubmitCopy(
            State.Texture, State.TextureWidth, State.TextureHeight);
        if (again == CK_OK) ++State.Draws;
        log_stats(State);
        return again == CK_OK ? TRUE : FALSE;
    }
    if (__atomic_load_n(&header->Magic, __ATOMIC_ACQUIRE) != MPF_MAGIC ||
        header->Version != MPF_VERSION) {
        if (!State.ReportedBadHeader) {
            State.ReportedBadHeader = TRUE;
            log_line("mailbox header mismatch (magic 0x%08x version %u)",
                     (unsigned)header->Magic, (unsigned)header->Version);
        }
        ++State.Rejected;
        return FALSE;
    }

    const uint32_t width = header->Width;
    const uint32_t height = header->Height;
    const uint32_t stride = header->Stride;
    const uint32_t slot = __atomic_load_n(&header->PublishedSlot, __ATOMIC_ACQUIRE);
    if (width < kMinExtent || height < kMinExtent || width > kMaxExtent ||
        height > kMaxExtent || header->Format != MPF_FORMAT_BGRA8 ||
        stride < width * 4u || slot >= MPF_SLOTS) {
        ++State.Rejected;
        return FALSE;
    }

    const size_t offset = (size_t)header->SlotOffset[slot];
    const size_t slotBytes = (size_t)stride * (size_t)height;
    if (offset < MPF_HEADER_BYTES || offset + slotBytes > State.Bytes) {
        ++State.Rejected;
        return FALSE;
    }
    if (!ensure_texture(Context, State, width, height)) {
        ++State.Rejected;
        return FALSE;
    }

    // The slot may be recycled by the next publish while this upload copies it;
    // with MPF_SLOTS == 3 that needs the consumer three frames behind.
    VxImageDescEx image;
    VxPixelFormat2ImageDesc(_32_ARGB8888, image);
    image.Width = (int)width;
    image.Height = (int)height;
    image.BytesPerLine = (int)stride;
    image.Image = (XBYTE *)((const char *)State.Base + offset);

    State.LastFrameId = frameId;
    if (!Context.LoadTexture(State.Texture, image, 0, CKRST_CUBEFACE_XPOS, nullptr)) {
        if (!State.ReportedUploadFailure) {
            State.ReportedUploadFailure = TRUE;
            log_line("LoadTexture %ux%u failed", width, height);
        }
        ++State.Rejected;
        return FALSE;
    }
    ++State.Frames;

    const CKERROR drawn = Context.m_Present.SubmitCopy(State.Texture, width, height);
    if (drawn == CK_OK) {
        ++State.Draws;
        State.HasContent = TRUE;
    }
    log_stats(State);
    return drawn == CK_OK ? TRUE : FALSE;
}
