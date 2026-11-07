#!/usr/bin/env python3
"""Measures whether the Minecraft half of the passthrough projects like the Ballance half.

Ballance's own projection is fully determined by the numbers it publishes (see
docs/scale-calibration.md and the VxMatrix::Perspective comments in
CameraSync.verticalFov): with a horizontal field of view fovH and a frame W x H,

    f = (W / 2) / tan(fovH / 2)          [px, both axes: square pixels]
    px = W/2 + f * (right . v) / (forward . v)
    py = H/2 - f * (up    . v) / (forward . v)

The Minecraft side has to agree with that, or a cube looks like a different size than the
ball that is exactly one block across. This script takes a frame plus the block that was
placed in it (the placement ray logs its BlockPos), projects that block with the Ballance
formula, and compares the result with the silhouette actually in the frame.

    ./fit-projection.py --png frame.png --block 3,98,-1 --eye 13.9,69.4,0.1 \
                        --ball 35.9,34.4,0.1 --fov 58 --size 640x480 --units 4

Prints the predicted and the measured box and the f each axis implies.
"""
import argparse
import math
import sys

import numpy as np
from PIL import Image

ANCHOR = (0.0, 100.0, 0.0)


def cube_corners(bx, by, bz):
    for dx in (0.0, 1.0):
        for dy in (0.0, 1.0):
            for dz in (0.0, 1.0):
                yield (bx + dx, by + dy, bz + dz)


def camera_basis(eye_u, ball_u, units):
    """Ballance's camera, in Minecraft block coordinates (the mapper's rule)."""
    ex = ANCHOR[0] + (eye_u[0] - ball_u[0]) / units
    ey = ANCHOR[1] + (eye_u[1] - ball_u[1]) / units
    ez = ANCHOR[2] - (eye_u[2] - ball_u[2]) / units
    return np.array([ex, ey, ez])


def project(points, eye, forward, up, f, width, height):
    right = np.cross(forward, up)
    right /= np.linalg.norm(right)
    out = []
    for p in points:
        v = np.asarray(p) - eye
        along = float(np.dot(v, forward))
        if along <= 1e-6:
            continue
        out.append((width / 2.0 + f * float(np.dot(v, right)) / along,
                    height / 2.0 - f * float(np.dot(v, up)) / along))
    return out


def bbox(points):
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return min(xs), max(xs), min(ys), max(ys)


def measure(png, window):
    im = Image.open(png).convert("RGB")
    a = np.array(im).astype(int)
    x0, x1, y0, y1 = window
    x0 = max(0, x0); y0 = max(0, y0)
    x1 = min(a.shape[1], x1); y1 = min(a.shape[0], y1)
    sub = a[y0:y1, x0:x1]
    lum = sub.mean(axis=2)
    bg = np.median(np.concatenate([sub[0, :, :], sub[-1, :, :]]).mean(axis=1))
    mask = lum < bg - 40
    # Largest 4-connected component only: the frame also carries the tutorial overlay and the
    # held item, and a box over every dark pixel stretches to include them.
    seen = np.zeros_like(mask)
    best = None
    height, width = mask.shape
    for sy in range(height):
        for sx in range(width):
            if not mask[sy, sx] or seen[sy, sx]:
                continue
            stack = [(sx, sy)]
            seen[sy, sx] = True
            xs = []
            ys = []
            while stack:
                cx, cy = stack.pop()
                xs.append(cx)
                ys.append(cy)
                for nx, ny in ((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)):
                    if 0 <= nx < width and 0 <= ny < height and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((nx, ny))
            if best is None or len(xs) > best[4]:
                best = (min(xs), max(xs), min(ys), max(ys), len(xs))
    if best is None:
        return None
    return (x0 + best[0], x0 + best[1], y0 + best[2], y0 + best[3]), bg, best[4]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--png", required=True)
    ap.add_argument("--block", required=True, help="BlockPos as x,y,z")
    ap.add_argument("--eye", required=True, help="Ballance camera eye, units")
    ap.add_argument("--ball", required=True, help="ball centre, units")
    ap.add_argument("--fov", type=float, default=58.0, help="Ballance FOV (horizontal), degrees")
    ap.add_argument("--size", default="640x480")
    ap.add_argument("--units", type=float, default=4.0, help="Ballance units per block")
    ap.add_argument("--forward", required=True, help="Ballance camera forward, units")
    ap.add_argument("--up", required=True, help="Ballance camera up, units")
    args = ap.parse_args()

    block = [int(v) for v in args.block.split(",")]
    eye_u = [float(v) for v in args.eye.split(",")]
    ball_u = [float(v) for v in args.ball.split(",")]
    width, height = [int(v) for v in args.size.split("x")]

    eye = camera_basis(eye_u, ball_u, args.units)
    # Virtools is left handed (+Z forward), Minecraft right handed: mirror Z, as the mapper does.
    fwd = np.array([float(v) for v in args.forward.split(",")])
    fwd = np.array([fwd[0], fwd[1], -fwd[2]])
    fwd /= np.linalg.norm(fwd)
    up = np.array([float(v) for v in args.up.split(",")])
    up = np.array([up[0], up[1], -up[2]])
    up /= np.linalg.norm(up)

    f = (width / 2.0) / math.tan(math.radians(args.fov) / 2.0)
    corners = list(cube_corners(*block))
    pts = project(corners, eye, fwd, up, f, width, height)
    px0, px1, py0, py1 = bbox(pts)

    print(f"frame          {width}x{height}")
    print(f"Ballance f     {f:.1f} px  (horizontal fov {args.fov:.2f} deg)")
    print(f"camera (blocks) {eye[0]:.2f}, {eye[1]:.2f}, {eye[2]:.2f}")
    print(f"block          {block}")
    print(f"predicted box  x {px0:.1f}..{px1:.1f}  y {py0:.1f}..{py1:.1f}"
          f"   ({px1-px0:.1f} x {py1-py0:.1f} px)")

    pad = 0.35 * max(px1 - px0, py1 - py0) + 6
    window = (int(px0 - pad), int(px1 + pad), int(py0 - pad), int(py1 + pad))
    got = measure(args.png, window)
    if not got:
        print("measured       nothing found in the window")
        return 1
    (mx0, mx1, my0, my1), bg, count = got
    print(f"measured box   x {mx0}..{mx1}  y {my0}..{my1}   ({mx1-mx0} x {my1-my0} px, bg {bg:.0f})")
    print(f"delta          left {mx0-px0:+.1f}  right {mx1-px1:+.1f}"
          f"  top {my0-py0:+.1f}  bottom {my1-py1:+.1f}")
    print(f"implied fx     {f * (mx1-mx0) / max(px1-px0, 1e-6):.1f} px"
          f"   fy {f * (my1-my0) / max(py1-py0, 1e-6):.1f} px   (Ballance: {f:.1f})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
