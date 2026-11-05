#ifndef CKSDLGPUPASSTHROUGH_H
#define CKSDLGPUPASSTHROUGH_H

#include "CKTypes.h"

#include <stddef.h>
#include <stdint.h>

class CKSdlGpuRasterizerContext;

// Consumer half of the Ballance <-> Minecraft frame mailbox (mpframe.h).
//
// The producer (the Fabric mod in ~/mcmod) publishes whole BGRA8 frames into a
// mailbox; this draws the newest one over the finished scene, inside the overlay
// pass, so the game's own 2D interface still lands on top of it.
//
// The mailbox is a POSIX shared memory object on Linux (/dev/shm/<name>) and a
// memory-mapped file on Windows (%LOCALAPPDATA%/BallanceMcPassthrough/<name>.dat).
// The layout is identical on both; only the transport differs, so the producer
// just has to write to the same path.
//
// Off by default. BALLANCE_MC_PASSTHROUGH=1 turns it on, BALLANCE_MC_SHM
// overrides the mailbox name (default "ballance_mc_frame") and
// BALLANCE_MC_MAILBOX an absolute path. The state is only touched from the
// render thread.
struct CKSdlGpuPassthroughState {
    CKBOOL Enabled = FALSE;
    CKBOOL Initialised = FALSE;
    CKBOOL Mapped = FALSE;
    // POSIX: file descriptor of the shared memory object, -1 when unmapped.
    int Fd = -1;
    // Windows: handle of the file the view is mapped from (nullptr when unmapped).
    void *FileHandle = nullptr;
    void *Base = nullptr;
    size_t Bytes = 0;
    CKDWORD Texture = 0;
    CKDWORD TextureWidth = 0;
    CKDWORD TextureHeight = 0;
    uint32_t LastFrameId = 0;
    // TRUE once a frame has been uploaded and drawn at least once, so ticks that
    // carry no new data can keep re-presenting the cached texture.
    CKBOOL HasContent = FALSE;
    uint32_t Ticks = 0;
    uint32_t OpenAttempts = 0;
    CKBOOL ReportedMissing = FALSE;
    CKBOOL ReportedBadHeader = FALSE;
    CKBOOL ReportedUploadFailure = FALSE;
    uint64_t Frames = 0;
    uint64_t Draws = 0;
    uint64_t Redraws = 0;
    uint64_t Misses = 0;
    uint64_t Rejected = 0;
    uint64_t FramesAtLastLog = 0;
    int64_t LastLogMs = 0;
    int64_t LastStaleCheckMs = 0;
    int64_t StartMs = 0;
    // Room for a full Windows path (%LOCALAPPDATA% is long). On POSIX this is the
    // filesystem path shm_open maps, and ShmName is the name handed to shm_open
    // itself - shm_open takes an object name, never a path.
    char Path[320] = {};
    char ShmName[320] = {};
};

// Draws the newest published frame into the currently open pass. Returns TRUE
// when a frame was uploaded and drawn this tick.
CKBOOL CKSdlGpuPassthroughTick(CKSdlGpuRasterizerContext &Context,
                               CKSdlGpuPassthroughState &State);

#endif // CKSDLGPUPASSTHROUGH_H
