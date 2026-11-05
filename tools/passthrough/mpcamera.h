#ifndef MPCAMERA_H
#define MPCAMERA_H

// Ballance -> Minecraft camera channel (milestone M1).
//
// The frame mailbox (mpframe.h) carries pixels one way, Minecraft -> Ballance.
// M1 needs the opposite direction: the viewpoint the Ballance scene was rendered
// from, plus where the player ball is, so the Minecraft client can render its own
// world from the same viewpoint. That is a second, much smaller shared object:
//
//   [ MpCameraHeader 4096 B ]      (no slots; the payload is scalar)
//
// Writer is the Ballance side (Source/Player/src/MinecraftCameraSync.cpp, installed
// from this directory); reader is the Fabric mod. Linux: POSIX shm object
// /dev/shm/<name>. Windows: memory-mapped file in
// %LOCALAPPDATA%\\BallanceMcPassthrough\\<name>.dat -- same split, same reasons,
// as mpframe.h.
//
// Ordering. The payload is published with a sequence number (seqlock): the writer
// bumps Seq with release semantics after filling the fields; the reader samples
// Seq with acquire semantics before and after copying, and retries when it moved.
// A torn sample is therefore never consumed, and the reader never blocks the
// render thread it is watching.
//
// Coordinates are Ballance world units (Virtools, row vectors, left handed,
// +Z forward, +Y up). The conversion to Minecraft blocks is the reader's job:
// UnitsPerBlock says how many Ballance units make one block (see
// docs/scale-calibration.md: 4.0, i.e. the ball is one block across).

#include <stdint.h>

#define MPC_MAGIC 0x3143504Du // 'M''P''C''1' little endian
#define MPC_VERSION 1u
#define MPC_BYTES 4096u

// Flags
#define MPC_FLAG_VALID 0x1u      // Eye/Forward/Up/FovY are meaningful
#define MPC_FLAG_BALL 0x2u       // Ball is meaningful
#define MPC_FLAG_PAUSED 0x4u     // the level is not advancing

typedef struct MpCameraHeader {
    uint32_t Magic;         // MPC_MAGIC
    uint32_t Version;       // MPC_VERSION
    uint32_t Seq;           // seqlock counter, release-published last
    uint32_t Flags;         // MPC_FLAG_*
    uint32_t Width;         // Ballance render target, pixels
    uint32_t Height;        // ditto
    float Eye[3];           // camera position, units
    float Forward[3];       // unit view direction, units
    float Up[3];            // unit up vector, units
    float FovY;             // vertical field of view, radians
    float Aspect;           // Width / Height of the projection used
    float Near;             // front plane, units
    float Far;              // back plane, units
    float Ball[3];          // player ball centre, units
    float UnitsPerBlock;    // 4.0 (docs/scale-calibration.md)
    uint32_t Frame;         // Ballance frame counter
    uint64_t TimeNs;        // CLOCK_MONOTONIC of the publish
    uint32_t Reserved[30];  // keeps the struct well inside MPC_BYTES
} MpCameraHeader;

#if defined(__cplusplus)
static_assert(sizeof(MpCameraHeader) <= MPC_BYTES, "MpCameraHeader must fit in MPC_BYTES");
#endif

#endif // MPCAMERA_H
