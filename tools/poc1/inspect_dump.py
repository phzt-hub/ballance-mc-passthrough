#!/usr/bin/env python3
"""Inspect one PoC-1 frame: numbers first, ASCII second, no image viewer needed.

Usage: inspect_dump.py /tmp/poc1/out 2400
"""
import json
import sys
from pathlib import Path

import numpy as np

CHARS = " .:-=+*#%@"


def load(path):
    meta = json.loads(path.with_suffix(".json").read_text())
    raw = path.read_bytes()
    w, h, bpp = meta["width"], meta["height"], meta["bytes_per_pixel"]
    px = np.frombuffer(raw, dtype=np.uint8).reshape(h, w, bpp)
    fmt = meta["sdl_format"]
    if fmt.startswith("D"):
        u32 = px.view(np.uint32).reshape(h, w)
        # 24-bit depth in the LOW three bytes, byte 3 unused (measured, not assumed).
        return (u32 & 0xFFFFFF).astype(np.float64) / 16777215.0, meta
    colour = px[:, :, :3].astype(np.float64) / 255.0
    if fmt == "B8G8R8A8_UNORM":
        colour = colour[:, :, [2, 1, 0]]
    return colour, meta


def gradient(a):
    gx = np.zeros_like(a)
    gy = np.zeros_like(a)
    gx[:, 1:-1] = a[:, 2:] - a[:, :-2]
    gy[1:-1, :] = a[2:, :] - a[:-2, :]
    return np.hypot(gx, gy)


def ascii_view(a, cols=48, rows=20, invert=False):
    h, w = a.shape
    ys = np.linspace(0, h, rows + 1).astype(int)
    xs = np.linspace(0, w, cols + 1).astype(int)
    out = []
    for i in range(rows):
        line = ""
        for j in range(cols):
            value = a[ys[i]:ys[i + 1], xs[j]:xs[j + 1]].mean()
            if invert:
                value = 1.0 - value
            line += CHARS[min(9, max(0, int(value * 9.999)))]
        out.append(line)
    return "\n".join(out)


def main():
    directory = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/poc1/out")
    frame = int(sys.argv[2] if len(sys.argv) > 2 else 0)
    depth_path = directory / ("native-depth-%06d.bin" % frame)
    colour_path = directory / ("native-color-%06d.bin" % frame)
    depth, dmeta = load(depth_path)
    lum = load(colour_path)[0].mean(axis=2)

    print("%s: %dx%d %s samples=%d" % (depth_path.name, dmeta["width"], dmeta["height"],
                                       dmeta["sdl_format"], dmeta["samples"]))
    qs = np.percentile(depth, [1, 10, 50, 90, 99])
    print("  depth p1/p10/p50/p90/p99 = " + " ".join("%.4f" % v for v in qs))
    print("  distinct depth values=%d geometry coverage (depth<1)=%.3f"
          % (np.unique(np.round(depth, 6)).size, float((depth < 0.999).mean())))
    corr_asis = np.corrcoef(gradient(depth).ravel(), gradient(lum).ravel())[0, 1]
    corr_flip = np.corrcoef(gradient(depth[::-1]).ravel(), gradient(lum).ravel())[0, 1]
    print("  edge correlation depth vs colour: as-is=%+.4f flipped=%+.4f -> rows are %s"
          % (corr_asis, corr_flip, "top-down" if abs(corr_asis) > abs(corr_flip) else "bottom-up"))
    print("\ncolour (dark to bright):")
    print(ascii_view(lum))
    print("\ndepth (inverted: @ near, space far):")
    print(ascii_view(depth, invert=True))


if __name__ == "__main__":
    main()
