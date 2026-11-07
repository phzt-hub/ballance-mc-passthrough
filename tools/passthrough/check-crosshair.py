#!/usr/bin/env python3
"""Where should the crosshair be, and where is the ball? (2026-11-07)

The passthrough draws the Minecraft crosshair on top of Ballance's frame. It has to sit on
the block the aim would place -- which is a point anchored to the ball -- so that it follows
the ball when Ballance's camera lags behind it.

This script is independent of both halves: it samples the camera channel (MpCamera.h), grabs
the composited window, and computes

    the ball's screen position   project(ball)
    the crosshair's position     project(ball + eye height + reach * aim direction)

with the same projection Ballance uses (square pixels, f = (W/2)/tan(fovH/2), see
docs/scale-calibration.md and fit-projection.py). It annotates the frame with those two points
plus the position the crosshair USED to have (dead centre, lifted) so the three can be compared
by eye, and prints the numbers.

    ./check-crosshair.py --window 0,0,640,480 --tag a
    ./check-crosshair.py --frames 6 --interval 0.4 --out /tmp/cross

An optional --expect FILE writes a small JSON with the numbers for a later comparison.
"""
import argparse
import json
import math
import os
import struct
import subprocess
import sys
import time

MPC_BYTES = 4096
LAYOUT = "<IIIIII" + "3f" * 3 + "4f" + "3f" + "f" + "I" + "Q"
FLAG_VALID, FLAG_BALL, FLAG_PAUSED, FLAG_LEVEL = 0x1, 0x2, 0x4, 0x8
UNITS_PER_BLOCK = 4.0          # MpCameraHeader.UnitsPerBlock
EYE_HEIGHT_BLOCKS = 1.62       # the player entity stands on the ball's centre
REACH_BLOCKS = 4.5


def header_path():
    path = os.environ.get("BALLANCE_MC_CAMERA_PATH")
    if path:
        return path
    local = os.environ.get("LOCALAPPDATA")
    if os.name == "nt" and local:
        return os.path.join(local, "BallanceMcPassthrough", "ballance_mc_camera.dat")
    return "/dev/shm/ballance_mc_camera"


def read_pose(path):
    with open(path, "rb") as handle:
        raw = handle.read(MPC_BYTES)
    before = struct.unpack_from("<I", raw, 8)[0]
    value = struct.unpack_from(LAYOUT, raw, 0)
    after = struct.unpack_from("<I", raw, 8)[0]
    if before != after or before == 0:
        return None
    lift = struct.unpack_from("<f", raw, 104)[0]
    return value + (lift,)


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def project(point, eye, forward, up, f, width, height):
    right = cross(forward, up)
    v = (point[0] - eye[0], point[1] - eye[1], point[2] - eye[2])
    along = dot(v, forward)
    if along <= 1e-6:
        return None
    return (width / 2.0 + f * dot(v, right) / along,
            height / 2.0 - f * dot(v, up) / along)


def aim_point(pose, units=UNITS_PER_BLOCK):
    """The point Minecraft's placement ray lands on, in Ballance units."""
    (magic, version, seq, flags, width, height, ex, ey, ez, fx, fy, fz,
     ux, uy, uz, fov, aspect, near, far, bx, by, bz, upb, frame, tns, lift) = pose
    scale = upb if upb > 0 else units
    forward = (fx, fy, fz)
    up = (ux, uy, uz)
    c, s = math.cos(lift), math.sin(lift)
    direction = (c * forward[0] + s * up[0],
                 c * forward[1] + s * up[1],
                 c * forward[2] + s * up[2])
    eye = (bx, by + EYE_HEIGHT_BLOCKS * scale, bz)
    reach = REACH_BLOCKS * scale
    return (eye[0] + direction[0] * reach,
            eye[1] + direction[1] * reach,
            eye[2] + direction[2] * reach)


def old_crosshair(pose):
    """Where the previous code drew it: dead centre, lifted by the aim angle."""
    (magic, version, seq, flags, width, height, ex, ey, ez, fx, fy, fz,
     ux, uy, uz, fov, aspect, near, far, bx, by, bz, upb, frame, tns, lift) = pose
    forward = (fx, fy, fz)
    up = (ux, uy, uz)
    point = aim_point(pose)
    right = cross(forward, up)
    v = (point[0] - ex, point[1] - ey, point[2] - ez)
    along = dot(v, forward)
    above = dot(v, up)
    if along <= 1e-6:
        return None
    angle = math.atan2(above, along)
    tan_half_vertical = math.tan(fov / 2.0) / aspect if aspect > 0 else math.tan(fov / 2.0)
    gui_height = height / 2.0          # GUI scale 2 in a 640x480 window; the pixels are what matter
    offset = gui_height * 0.5 * math.tan(angle) / tan_half_vertical
    return (width / 2.0, height / 2.0 - offset * 2.0)


def capture(path, window):
    x, y, w, h = window
    geometry = "%dx%d+%d,%d" % (w, h, x, y)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "x11grab",
                    "-video_size", "%dx%d" % (w, h), "-i", "%s+%d,%d" % (os.environ.get("DISPLAY", ":99"), x, y),
                    "-frames:v", "1", path], check=True)
    return path


def annotate(path, numbers):
    from PIL import Image, ImageDraw
    image = Image.open(path).convert("RGB")
    draw = ImageDraw.Draw(image)
    width, height = image.size
    if numbers["ball"]:
        bx, by = numbers["ball"]
        draw.ellipse([bx - 7, by - 7, bx + 7, by + 7], outline=(0, 255, 255), width=2)
    if numbers["aim"]:
        ax, ay = numbers["aim"]
        draw.rectangle([ax - 8, ay - 8, ax + 8, ay + 8], outline=(255, 0, 255), width=2)
    if numbers["old"]:
        ox, oy = numbers["old"]
        draw.rectangle([ox - 6, oy - 6, ox + 6, oy + 6], outline=(255, 255, 0), width=1)
    # No centre lines: the crosshair is what is being looked at, and a line through the
    # middle of the frame is exactly what a "pinned to the centre" crosshair looks like.
    image.save(path)
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--window", default="0,0,640,480", help="x,y,w,h of the Ballance window")
    ap.add_argument("--frames", type=int, default=1)
    ap.add_argument("--interval", type=float, default=0.5)
    ap.add_argument("--out", default="/tmp/crosshair")
    ap.add_argument("--tag", default="shot")
    ap.add_argument("--json")
    args = ap.parse_args()

    window = tuple(int(part) for part in args.window.split(","))
    os.makedirs(args.out, exist_ok=True)
    path = header_path()
    if not os.path.exists(path):
        print("no camera channel at %s (is Ballance running with BALLANCE_MC_CAMERA=1?)" % path)
        return 1

    rows = []
    for index in range(args.frames):
        png = os.path.join(args.out, "%s-%02d.png" % (args.tag, index))
        capture(png, window)
        pose = read_pose(path)
        if pose is None:
            print("%s: no coherent pose" % png)
            continue
        (magic, version, seq, flags, width, height, ex, ey, ez, fx, fy, fz,
         ux, uy, uz, fov, aspect, near, far, bx, by, bz, upb, frame, tns, lift) = pose
        forward = (fx, fy, fz)
        up = (ux, uy, uz)
        f = (width / 2.0) / math.tan(fov / 2.0)
        numbers = {
            "png": png,
            "level": bool(flags & FLAG_LEVEL),
            "ball": project((bx, by, bz), (ex, ey, ez), forward, up, f, width, height),
            "aim": project(aim_point(pose), (ex, ey, ez), forward, up, f, width, height),
            "old": old_crosshair(pose),
            "lift_deg": math.degrees(lift),
            "fov_deg": math.degrees(fov),
            "frame": frame,
        }
        annotate(png, numbers)
        rows.append(numbers)
        fmt = "%-10s ball=(%6.1f,%6.1f)  aim=(%6.1f,%6.1f)  old=(%6.1f,%6.1f)  lift=%4.1f° level=%d"
        print(fmt % (os.path.basename(png),
                     numbers["ball"][0] if numbers["ball"] else float("nan"),
                     numbers["ball"][1] if numbers["ball"] else float("nan"),
                     numbers["aim"][0] if numbers["aim"] else float("nan"),
                     numbers["aim"][1] if numbers["aim"] else float("nan"),
                     numbers["old"][0] if numbers["old"] else float("nan"),
                     numbers["old"][1] if numbers["old"] else float("nan"),
                     numbers["lift_deg"], 1 if numbers["level"] else 0))
        if index + 1 < args.frames:
            time.sleep(args.interval)

    if len(rows) > 1:
        xs = [row["ball"][0] for row in rows if row["ball"]]
        axs = [row["aim"][0] for row in rows if row["aim"]]
        print("ball x span %.1f px, aim x span %.1f px (the aim point must move with the ball)"
              % (max(xs) - min(xs), max(axs) - min(axs)))
        drift = [math.hypot(row["ball"][0] - row["old"][0], row["ball"][1] - row["old"][1])
                 for row in rows if row["ball"] and row["old"]]
        if drift:
            print("old crosshair to ball distance: %.1f..%.1f px (it never followed the ball)"
                  % (min(drift), max(drift)))
    if args.json:
        with open(args.json, "w") as handle:
            json.dump(rows, handle, indent=1)
    print("images in %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
