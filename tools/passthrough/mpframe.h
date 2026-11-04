#ifndef MPFRAME_H
#define MPFRAME_H

// Ballance <-> Minecraft frame mailbox.
//
// The producer (the Minecraft export mod, or tools/passthrough/shmfeed.c while
// that does not exist yet) owns a POSIX shared memory object laid out as
//
//   [ MpFrameHeader ][ slot 0 ][ slot 1 ][ slot 2 ]
//
// and publishes whole BGRA8 frames by filling a free slot and then bumping
// FrameId with release semantics. The consumer (the Ballance SDL_GPU
// rasterizer) reads FrameId with acquire semantics, copies the published slot
// into a texture and draws it over the scene.
//
// Invariant: a slot may be overwritten while a slow consumer is still reading
// it. With MPF_SLOTS == 3 that needs a consumer three frames behind, which is
// what the M0 budget assumes; deeper buffering is a header version bump.
//
// This header is included by C and by C++ and must stay POD.

#include <stdint.h>

#define MPF_MAGIC 0x3050464Du // 'M''F''P''0' little endian
#define MPF_VERSION 1u
#define MPF_SLOTS 3
#define MPF_FORMAT_BGRA8 1u
#define MPF_HEADER_BYTES 4096u

typedef struct MpFrameHeader {
    uint32_t Magic;          // MPF_MAGIC
    uint32_t Version;        // MPF_VERSION
    uint32_t Width;          // slot width in pixels
    uint32_t Height;         // slot height in pixels
    uint32_t Stride;         // bytes per row (>= Width * 4)
    uint32_t Format;         // MPF_FORMAT_*
    uint32_t SlotBytes;      // Stride * Height
    uint32_t SlotCount;      // MPF_SLOTS
    uint32_t FrameId;        // published frame counter, 0 = nothing published
    uint32_t PublishedSlot;  // slot holding FrameId
    uint32_t ProducerPid;
    uint32_t Flags;
    uint64_t TimestampNs;    // CLOCK_MONOTONIC of the publish
    uint64_t SlotOffset[MPF_SLOTS];
    uint32_t Reserved[8];
} MpFrameHeader;

#endif // MPFRAME_H
