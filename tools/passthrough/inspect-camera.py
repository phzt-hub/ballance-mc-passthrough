#!/usr/bin/env python3
"""Read the Ballance -> Minecraft camera channel (MpCamera.h) independently.

The writer is Source/Player/src/MinecraftCameraSync.cpp (enabled with
BALLANCE_MC_CAMERA=1), the reader is the Fabric mod. This script is neither: it
opens the same object, validates the seqlock and prints the pose, so "the mod
does not see it" and "it was never published" stay distinguishable.

    ./inspect-camera.py                 # one sample
    ./inspect-camera.py --watch 3       # sample for 3 s, report rate

Offsets mirror MpCameraHeader; a change there has to be repeated here (and in
McCameraChannel.java on the mod side).
"""

import argparse
import math
import os
import struct
import sys
import time

MPC_MAGIC = 0x3143504D
MPC_BYTES = 4096
FLAG_VALID, FLAG_BALL, FLAG_PAUSED, FLAG_LEVEL = 0x1, 0x2, 0x4, 0x8

# M1c: which level the game loaded, from the number in its file name. 0 = no level
# loaded yet (menus, and the frames around a level start).
MPC_OFF_LEVEL_ID = 132

LAYOUT = "<IIIIII" + "3f" * 3 + "4f" + "3f" + "f" + "I" + "Q"
# magic, version, seq, flags, width, height, eye, forward, up, fovy, aspect, near,
# far, ball, unitsPerBlock, frame, timeNs


def header_path(name: str) -> str:
    path = os.environ.get("BALLANCE_MC_CAMERA_PATH")
    if path:
        return path
    local = os.environ.get("LOCALAPPDATA")
    if os.name == "nt" and local:
        return os.path.join(local, "BallanceMcPassthrough", name + ".dat")
    return os.path.join("/dev/shm", name)


def read_once(fd) -> tuple:
    """seqlock sample: retry while Seq moves underneath us."""
    for _ in range(64):
        os.lseek(fd, 0, os.SEEK_SET)
        raw = os.read(fd, MPC_BYTES)
        if len(raw) < 128:
            raise SystemExit("short read: %d bytes" % len(raw))
        before = struct.unpack_from("<I", raw, 8)[0]
        value = struct.unpack_from(LAYOUT, raw, 0)
        level_id = struct.unpack_from("<I", raw, MPC_OFF_LEVEL_ID)[0]
        after = struct.unpack_from("<I", raw, 8)[0]
        if before == after and before != 0:
            return value + (level_id,)
    raise SystemExit("the writer is publishing faster than this script can read")


def describe(v) -> str:
    (magic, version, seq, flags, width, height,
     ex, ey, ez, fx, fy, fz, ux, uy, uz,
     fovy, aspect, near, far, bx, by, bz, upb, frame, tns, level_id) = v
    lines = [
        "magic     0x%08X %s" % (magic, "ok" if magic == MPC_MAGIC else "MISMATCH"),
        "version   %d" % version,
        "seq       %d   frame %d" % (seq, frame),
        "level     %d%s" % (level_id, "" if level_id else "  (the game has not said)"),
        "flags     0x%X%s%s%s%s" % (
            flags,
            " VALID" if flags & FLAG_VALID else "",
            " BALL" if flags & FLAG_BALL else "",
            " LEVEL" if flags & FLAG_LEVEL else "",
            " PAUSED" if flags & FLAG_PAUSED else ""),
        "size      %dx%d  aspect %.4f" % (width, height, aspect),
        "eye       (%.3f, %.3f, %.3f)  u" % (ex, ey, ez),
        "forward   (%+.4f, %+.4f, %+.4f)  |f|=%.4f" % (fx, fy, fz, math.dist((0, 0, 0), (fx, fy, fz))),
        "up        (%+.4f, %+.4f, %+.4f)" % (ux, uy, uz),
        "fov       %.3f deg (vertical)  near %.2f far %.0f" % (math.degrees(fovy), near, far),
        "ball      (%.3f, %.3f, %.3f)  u" % (bx, by, bz),
    ]
    if flags & FLAG_BALL:
        lines.append("ball->eye (%.3f, %.3f, %.3f)  u = %.2f blocks"
                     % (ex - bx, ey - by, ez - bz,
                        math.dist((ex, ey, ez), (bx, by, bz)) / max(upb, 1e-6)))
    lines.append("units/blk %.3f   age %.3f s" % (upb, (time.monotonic_ns() - tns) / 1e9 if tns else -1))
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default=os.environ.get("BALLANCE_MC_CAMERA_SHM", "ballance_mc_camera"))
    ap.add_argument("--watch", type=float, default=0.0, help="sample this many seconds")
    args = ap.parse_args()

    path = header_path(args.name)
    if not os.path.exists(path):
        print("no camera channel at %s" % path, file=sys.stderr)
        return 1
    fd = os.open(path, os.O_RDONLY)
    try:
        first = read_once(fd)
        print("channel   %s" % path)
        print(describe(first))
        if args.watch > 0:
            seq0 = first[2]
            started = time.monotonic()
            time.sleep(args.watch)
            last = read_once(fd)
            elapsed = time.monotonic() - started
            moved = (last[2] - seq0) & 0xFFFFFFFF
            print("\nafter %.2f s: seq %d -> %d (%d publishes, %.1f /s)"
                  % (elapsed, seq0, last[2], moved, moved / max(elapsed, 1e-6)))
            print("eye moved %.3f u, ball moved %.3f u"
                  % (math.dist(first[6:9], last[6:9]), math.dist(first[19:22], last[19:22])))
    finally:
        os.close(fd)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
