#ifndef MPCAMERA_H
#define MPCAMERA_H

// Ballance -> Minecraft camera channel (milestone M1).
//
// The frame mailbox (mpframe.h, in tools/passthrough of ballance-mc-passthrough)
// carries pixels one way, Minecraft -> Ballance. M1 needs the opposite direction:
// the viewpoint the scene was just rendered from, plus where the player ball is,
// so the Minecraft client can render its own world from that same viewpoint.
//
//   [ MpCameraHeader   4096 B ]    the camera, plus the input mirror (M1b)
//     MpClientStatus  at 2048      written by Minecraft, read by this side
//
// Writer is the Ballance side (Source/Player/src/MinecraftCameraSync.cpp, installed
// from this directory); reader is the Fabric mod. Linux: POSIX shm object /dev/shm/<name>. Windows: memory-mapped file in
// %LOCALAPPDATA%\\BallanceMcPassthrough\\<name>.dat -- the same transport split,
// and the same reasons, as the frame mailbox.
//
// Ordering: seqlock. The writer fills the fields and then bumps Seq with release
// semantics; the reader samples Seq with acquire semantics before and after
// copying and retries when it moved. A torn sample is never consumed, and the
// writer never blocks the render thread it is publishing from.
//
// Coordinates are Ballance world units (Virtools, row vectors, left handed,
// +Z forward, +Y up). Converting to Minecraft blocks is the reader's job;
// UnitsPerBlock says how many units make one block. It defaults to 5.48, the width of a
// ball transformer's plate (P_Trafo_* in docs/scale-calibration.md), so a block is the same
// size as the transformer; 4.0 is the honest calibration (one ball across).
//
// M1b (2026-11-06) added two things to the same object, both in the space the
// version-1 payload left as Reserved:
//
//   * AimLift -- the number of radians the Minecraft camera is tilted up above
//     the Ballance view direction. The passthrough overlays one image on the
//     other, so the Minecraft crosshair used to sit exactly on the ball, and a
//     block placed at the crosshair landed on the ball. Tilting the Minecraft
//     camera (not the Ballance one) lifts the crosshair off the ball while the
//     Ballance frame keeps the viewpoint it was rendered from. The panel row
//     AimLift writes this number, so it is tunable while playing.
//
//   * the input mirror -- pointer position/buttons/wheel and the two keys the
//     Minecraft client cannot receive on its own, because the Ballance window
//     owns the keyboard. Minecraft publishes back whether the inventory it
//     opened is still up (MpClientStatus.GuiOpen); that is what tells this side
//     to freeze the level and hand the pointer over.
//
// Keep in sync with tools/passthrough/mpcamera.h (the tools-side mirror) and with
// McCameraChannel.java (the reader, which hardcodes these offsets).

#include <stdint.h>

#define MPC_MAGIC 0x3143504Du // 'M''P''C''1' little endian
#define MPC_VERSION 3u   // 3: LevelId (M1c)
#define MPC_BYTES 4096u

// Flags
#define MPC_FLAG_VALID 0x1u      // Eye/Forward/Up/FovY are meaningful
#define MPC_FLAG_BALL 0x2u       // Ball is meaningful
#define MPC_FLAG_PAUSED 0x4u     // the level is not advancing
#define MPC_FLAG_LEVEL 0x8u      // a level is running: the attached camera is a gameplay one
                                 // (the menu keeps Cam_MenuLevel attached, and it also has
                                 // ball-shaped objects parked at the origin, so "a ball
                                 // exists" is not "a level is running")

// Pointer buttons, as published in InputButtons.
#define MPC_BUTTON_LEFT 0x1u
#define MPC_BUTTON_RIGHT 0x2u
#define MPC_BUTTON_MIDDLE 0x4u

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
    float UnitsPerBlock;    // units across one drawn block: 5.48 = a transformer plate,
                            // 4.0 = one ball (the panel row Block steps it)
    uint32_t Frame;         // Ballance frame counter
    uint64_t TimeNs;        // CLOCK_MONOTONIC of the publish
    // --- M1b. These sit where version 1 had Reserved[0..], so every offset
    // above them is unchanged and an older reader simply ignores them.
    float AimLift;          // radians the Minecraft camera is tilted up; 0 = on the ball
    float MouseX;           // pointer in the Ballance window, 0..1, origin top left
    float MouseY;
    uint32_t InputButtons;  // MPC_BUTTON_*
    int32_t Wheel;          // wheel notches accumulated since the writer started
    uint32_t KeyE;          // monotonic press counters; the reader keeps its own copy
    uint32_t KeyEsc;
    // --- M1c (2026-11-08): which level the game is in.
    // The number in the level file the game loaded ("...\Level_03.nmo"), read from
    // the "Object Load" behavior that loaded it. 0 = "not known": menus, and the
    // frames before a level file has been loaded. The reader treats 0 as "no answer"
    // and falls back to identifying the level by position.
    uint32_t LevelId;
    uint32_t Reserved[21];  // keeps the struct well inside MPC_BYTES
} MpCameraHeader;

// Minecraft -> Ballance, at MPS_OFFSET in the same object. The camera channel
// cannot carry this: it is written by the other process, and the reader of this
// side needs to know whether the inventory is still up before it can decide to
// freeze the level. Own seqlock: Magic is written once, Seq last.
typedef struct MpClientStatus {
    uint32_t Magic;         // MPS_MAGIC, written once the client is alive
    uint32_t Seq;           // seqlock counter, release-published last
    uint32_t GuiOpen;       // 1 while the passthrough inventory is on screen
    uint32_t Ticks;         // client ticks since start
    uint32_t KeyE;          // the press counters the client has acted on
    uint32_t KeyEsc;
    uint32_t GuiWidth;      // the inventory's coordinate space, GUI pixels
    uint32_t GuiHeight;
    uint32_t Reserved[8];
} MpClientStatus;

#define MPS_OFFSET 2048u
#define MPS_MAGIC 0x3153504Du // 'M''P''S''1'

#if defined(__cplusplus)
static_assert(sizeof(MpCameraHeader) <= MPS_OFFSET, "MpCameraHeader must not reach MpClientStatus");
static_assert(MPS_OFFSET + sizeof(MpClientStatus) <= MPC_BYTES,
              "MpClientStatus must fit in MPC_BYTES");
#endif

#endif // MPCAMERA_H