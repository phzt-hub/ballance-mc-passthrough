// Host side of the Ballance <-> Minecraft passthrough (milestone M0).
//
// Reads the frame mailbox described by mpframe.h and draws the newest frame as
// a fullscreen quad over the resolved scene, before the game's 2D interface is
// drawn and before the frame is presented. Nothing here knows about geometry,
// cameras or depth yet: M0 only proves the transport plus one textured quad at
// an acceptable cost.
//
// Transport. Linux reads a POSIX shared memory object (/dev/shm/<name>), Windows
// reads a memory-mapped file (%LOCALAPPDATA%/BallanceMcPassthrough/<name>.dat)
// because Windows has no shm namespace. Both transports carry the same mailbox
// layout, so the producer side is one implementation that only differs in how it
// maps the bytes (shm_open vs FileChannel.map). Windows guarantees that two views
// of the same file - even from different section objects - see each other's
// writes through the file cache, which is all the mailbox needs.

#include "CKSdlGpuPassthrough.h"

#include "CKSdlGpuRasterizerContext.h"
#include "mpframe.h"

#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#include <intrin.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace {

constexpr uint32_t kMinExtent = 8;
constexpr uint32_t kMaxExtent = 8192;
// The mailbox is retried at this cadence while the producer is absent; the
// statistics line is printed at this cadence once it is present.
constexpr uint32_t kOpenRetryTicks = 30;
constexpr int64_t kStatsIntervalMs = 2000;
// How often the mapping is checked for a producer that restarted or resized.
constexpr int64_t kStaleCheckMs = 2000;

int64_t now_ms() {
    using clock = std::chrono::steady_clock;
    static const clock::time_point origin = clock::now();
    return (int64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
        clock::now() - origin).count();
}

void log_sink(const char *line);

void log_line(const char *fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    log_sink(buffer);
}

#if defined(_WIN32)
// The Windows build is a windowed process, so the stderr copy is usually
// swallowed (see Main.cpp: it only opens a console when asked to). Keep an
// append-only log next to the working directory so a bad first run can be read
// afterwards; the volume is two lines a second at worst.
void log_sink(const char *line) {
    fprintf(stderr, "[mc-passthrough] %s\n", line);
    fflush(stderr);
    if (FILE *file = fopen("mc-passthrough.log", "a")) {
        fprintf(file, "[mc-passthrough] %s\n", line);
        fclose(file);
    }
}
#else
void log_sink(const char *line) {
    fprintf(stderr, "[mc-passthrough] %s\n", line);
    fflush(stderr);
}
#endif

CKBOOL env_flag(const char *name) {
    const char *value = getenv(name);
    if (!value || !*value) return FALSE;
    if (!strcmp(value, "0") || !strcmp(value, "off") || !strcmp(value, "false") ||
        !strcmp(value, "no")) return FALSE;
    return TRUE;
}

// Acquire load of a header word. The producer fills a slot, then stores
// PublishedSlot, then bumps FrameId with release semantics; everything this
// consumer reads after the acquire must come after it in program order.
uint32_t load_acquire(const uint32_t *field) {
#if defined(_MSC_VER)
    // x86-64 is strongly ordered, so an aligned 32-bit load plus a compiler
    // barrier is a full acquire.
    //
    // Do not reach for _InterlockedCompareExchange here. It is a *locked
    // read-modify-write*, and this view is mapped read-only (CreateFileMapping
    // PAGE_READONLY -> MapViewOfFile FILE_MAP_READ), so its write half raises an
    // access violation on the very first tick. That is what killed the 2026-11-05
    // 14:26:27 run: WER reports CKSdlGpuRasterizer.dll+0x3bcd9, which decodes to
    // "lock cmpxchg %edi,0x20(%rcx)" right here - the mapping was fine, the read
    // had been turned into a write.
    const uint32_t value = *reinterpret_cast<const volatile uint32_t *>(field);
    _ReadBarrier();
    return value;
#else
    return __atomic_load_n(field, __ATOMIC_ACQUIRE);
#endif
}

// Resolves the mailbox the same way the producer does. The name comes from
// BALLANCE_MC_SHM (default "ballance_mc_frame"); BALLANCE_MC_MAILBOX replaces the
// whole path (a file path on Windows, a /dev/shm path on POSIX).
void resolve_path(CKSdlGpuPassthroughState &state) {
    const char *name = getenv("BALLANCE_MC_SHM");
    if (!name || !*name) name = "ballance_mc_frame";
    const char *explicit_path = getenv("BALLANCE_MC_MAILBOX");
    const bool has_explicit = explicit_path && *explicit_path;
#if defined(_WIN32)
    if (has_explicit) {
        snprintf(state.Path, sizeof(state.Path), "%s", explicit_path);
        return;
    }
    char base[MAX_PATH * 2] = {};
    DWORD length = GetEnvironmentVariableA("LOCALAPPDATA", base, (DWORD)sizeof(base));
    if (length == 0 || length >= sizeof(base)) {
        length = GetEnvironmentVariableA("TEMP", base, (DWORD)sizeof(base));
        if (length == 0 || length >= sizeof(base)) snprintf(base, sizeof(base), ".");
    }
    snprintf(state.Path, sizeof(state.Path), "%s\\BallanceMcPassthrough\\%s.dat", base, name);
#else
    // shm_open wants an object name such as "/ballance_mc_frame", not a path, so
    // the /dev/shm prefix is stripped from an explicit path and kept only for the
    // log line. BALLANCE_MC_SHM may carry either form.
    const char *shm_name = has_explicit ? explicit_path : name;
    if (!strncmp(shm_name, "/dev/shm/", 9)) shm_name += 9;
    if (*shm_name == '/') {
        snprintf(state.ShmName, sizeof(state.ShmName), "%s", shm_name);
        snprintf(state.Path, sizeof(state.Path), "/dev/shm%s", shm_name);
    } else {
        snprintf(state.ShmName, sizeof(state.ShmName), "/%s", shm_name);
        snprintf(state.Path, sizeof(state.Path), "/dev/shm/%s", shm_name);
    }
#endif
}

// What the log lines call the mailbox: the file path on Windows, the shared
// memory object name on POSIX (the same string the M0 docs and tests quote).
const char *mailbox_label(const CKSdlGpuPassthroughState &state) {
#if defined(_WIN32)
    (void)state.ShmName;
    return state.Path;
#else
    return state.ShmName;
#endif
}

void initialise(CKSdlGpuPassthroughState &state) {
    state.Initialised = TRUE;
    state.Enabled = env_flag("BALLANCE_MC_PASSTHROUGH");
    resolve_path(state);
    state.StartMs = now_ms();
    if (state.Enabled)
        log_line("enabled, mailbox %s (%s)", mailbox_label(state), state.Path);
}

// Releases the mapping without touching the cached texture: the producer may
// come back with a new size, and the last frame stays on screen meanwhile.
void release_mailbox(CKSdlGpuPassthroughState &state) {
    if (!state.Mapped) return;
#if defined(_WIN32)
    if (state.Base) UnmapViewOfFile(state.Base);
    if (state.FileHandle) CloseHandle(static_cast<HANDLE>(state.FileHandle));
    state.FileHandle = nullptr;
#else
    if (state.Base) munmap(state.Base, state.Bytes);
    if (state.Fd >= 0) close(state.Fd);
    state.Fd = -1;
#endif
    state.Base = nullptr;
    state.Bytes = 0;
    state.Mapped = FALSE;
    state.LastFrameId = 0;
    // Retry on the very next tick instead of waiting out the open backoff.
    state.OpenAttempts = 0;
    log_line("mailbox %s released", mailbox_label(state));
}

// TRUE when the mapping no longer points at what the producer is writing: the
// producer unlinks and recreates the mailbox whenever the frame size changes, and
// an unlinked object keeps the old bytes visible through the existing view.
CKBOOL mailbox_stale(CKSdlGpuPassthroughState &state) {
    if (!state.Mapped) return FALSE;
#if defined(_WIN32)
    BY_HANDLE_FILE_INFORMATION info;
    if (!GetFileInformationByHandle(static_cast<HANDLE>(state.FileHandle), &info)) return TRUE;
    const uint64_t size =
        ((uint64_t)info.nFileSizeHigh << 32) | (uint64_t)info.nFileSizeLow;
    return info.nNumberOfLinks == 0 || size != (uint64_t)state.Bytes;
#else
    struct stat info;
    if (fstat(state.Fd, &info) != 0) return TRUE;
    return info.st_nlink == 0 || (size_t)info.st_size != state.Bytes;
#endif
}

// Maps the mailbox if the producer has created it. Cheap enough to retry, but
// only every kOpenRetryTicks ticks so a missing producer costs nothing.
CKBOOL try_map(CKSdlGpuPassthroughState &state) {
    if (state.Mapped) return TRUE;
    if (state.OpenAttempts > 0 && (state.Ticks % kOpenRetryTicks) != 0) return FALSE;
    ++state.OpenAttempts;

#if defined(_WIN32)
    HANDLE handle = CreateFileA(state.Path, GENERIC_READ,
                                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        if (!state.ReportedMissing) {
            state.ReportedMissing = TRUE;
            log_line("waiting for %s (producer not running yet, error %lu)", state.Path,
                     (unsigned long)GetLastError());
        }
        return FALSE;
    }
    LARGE_INTEGER size = {};
    if (!GetFileSizeEx(handle, &size) || (uint64_t)size.QuadPart < MPF_HEADER_BYTES) {
        CloseHandle(handle);
        if (!state.ReportedBadHeader) {
            state.ReportedBadHeader = TRUE;
            log_line("%s is smaller than the header (%lld bytes)", state.Path,
                     (long long)size.QuadPart);
        }
        return FALSE;
    }
    HANDLE section = CreateFileMappingA(handle, nullptr, PAGE_READONLY, 0, 0, nullptr);
    if (!section) {
        log_line("CreateFileMapping %s failed (error %lu)", state.Path,
                 (unsigned long)GetLastError());
        CloseHandle(handle);
        return FALSE;
    }
    // The view keeps the section alive, so the section handle goes right away.
    void *base = MapViewOfFile(section, FILE_MAP_READ, 0, 0, 0);
    CloseHandle(section);
    if (!base) {
        log_line("MapViewOfFile %s failed (error %lu)", state.Path,
                 (unsigned long)GetLastError());
        CloseHandle(handle);
        return FALSE;
    }
    state.FileHandle = handle;
    state.Base = base;
    state.Bytes = (size_t)size.QuadPart;
#else
    const int fd = shm_open(state.ShmName, O_RDONLY, 0);
    if (fd < 0) {
        if (!state.ReportedMissing) {
            state.ReportedMissing = TRUE;
            log_line("waiting for %s (producer not running yet)", mailbox_label(state));
        }
        return FALSE;
    }
    struct stat info;
    if (fstat(fd, &info) != 0 || (size_t)info.st_size < MPF_HEADER_BYTES) {
        close(fd);
        if (!state.ReportedBadHeader) {
            state.ReportedBadHeader = TRUE;
            log_line("%s is smaller than the header (%lld bytes)", mailbox_label(state),
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
#endif
    state.Mapped = TRUE;
    state.ReportedMissing = FALSE;
    state.ReportedBadHeader = FALSE;
    state.LastStaleCheckMs = now_ms();
    log_line("mapped %s (%zu bytes)", mailbox_label(state), state.Bytes);
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

    // A producer that restarted (or resized the window) leaves this view on an
    // orphaned object. Drop it and pick the new one up on the next tick; the
    // cached texture keeps presenting the last frame meanwhile.
    const int64_t now = now_ms();
    if (State.Mapped && now - State.LastStaleCheckMs >= kStaleCheckMs) {
        State.LastStaleCheckMs = now;
        if (mailbox_stale(State)) release_mailbox(State);
    }
    if (!try_map(State)) return FALSE;

    const MpFrameHeader *header = static_cast<const MpFrameHeader *>(State.Base);
    const uint32_t frameId = load_acquire(&header->FrameId);
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
    if (load_acquire(&header->Magic) != MPF_MAGIC ||
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
    const uint32_t slot = load_acquire(&header->PublishedSlot);
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
