#!/usr/bin/env python3
"""Decode a PoC-1 raw depth/colour dump into a PNG plus a text report.

The raw bytes are written by tools/poc1/depth-dump.cpp; everything that could
have been guessed wrong there (row order, depth encoding) is decided here, so a
mistake costs a rerun of this script instead of a rebuild.

Usage: decode_depth.py <dump.bin> [--out out.png] [--flip] [--report]
"""
import argparse
import json
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image


def decode_depth(raw: bytes, meta: dict) -> np.ndarray:
    fmt = meta["sdl_format"]
    w, h = meta["width"], meta["height"]
    bpp = meta["bytes_per_pixel"]
    buf = np.frombuffer(raw, dtype=np.uint8)
    expected = w * h * bpp
    if buf.size != expected:
        raise SystemExit(f"size mismatch: {buf.size} != {expected} (w={w} h={h} bpp={bpp})")
    px = buf.reshape(h, w, bpp)
    if fmt.startswith("D24"):
        # 24-bit depth in the LOW three bytes of a little-endian u32, byte 3
        # unused. Measured with probe_depth.cpp: clear 1.0 -> ff ff ff 00.
        u32 = px.view(np.uint32).reshape(h, w)
        return (u32 & 0xFFFFFF).astype(np.float64) / 16777215.0
    if fmt.startswith("D32_FLOAT"):
        return px[:, :, :4].copy().view(np.float32).reshape(h, w).astype(np.float64)
    if fmt == "D16_UNORM":
        u16 = px.view(np.uint16).reshape(h, w)
        return u16.astype(np.float64) / 65535.0
    raise SystemExit(f"not a depth format: {fmt}")


def decode_color(raw: bytes, meta: dict) -> np.ndarray:
    fmt = meta["sdl_format"]
    w, h = meta["width"], meta["height"]
    px = np.frombuffer(raw, dtype=np.uint8).reshape(h, w, 4)
    if fmt == "B8G8R8A8_UNORM":
        px = px[:, :, [2, 1, 0, 3]]
    return px


def report(name: str, depth: np.ndarray) -> None:
    finite = depth[np.isfinite(depth)]
    if finite.size == 0:
        print(f"{name}: all non-finite")
        return
    uniq = np.unique(np.round(finite, 6))
    qs = np.percentile(finite, [0, 1, 10, 50, 90, 99, 100])
    print(f"{name}: {depth.shape[1]}x{depth.shape[0]}")
    print(f"  min/p1/p10/p50/p90/p99/max = " + " ".join(f"{v:.6f}" for v in qs))
    print(f"  mean={finite.mean():.6f} distinct(rounded)={uniq.size} "
          f"frac==1.0={float(np.mean(finite >= 0.999999)):.4f} frac==0={float(np.mean(finite <= 1e-6)):.4f}")
    # Vertical profile: tells us which way the image is stored when the scene
    # has a sky (far) at the top and a track (near) at the bottom.
    rows = depth.mean(axis=1)
    top, bottom = rows[: max(1, rows.size // 8)].mean(), rows[-max(1, rows.size // 8):].mean()
    print(f"  row means: first 1/8={top:.6f} last 1/8={bottom:.6f} "
          f"-> {'rows run bottom-up' if top > bottom else 'rows run top-down' if bottom > top else 'flat'}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("raw", help="<label>-<frame>.bin written by the hook")
    ap.add_argument("--out", default=None, help="PNG path (default: alongside the .bin)")
    ap.add_argument("--flip", action="store_true", help="flip rows before writing")
    args = ap.parse_args()

    raw_path = Path(args.raw)
    meta_path = raw_path.with_suffix(".json")
    meta = json.loads(meta_path.read_text())
    raw = raw_path.read_bytes()
    out = Path(args.out) if args.out else raw_path.with_suffix(".png")

    if meta["sdl_format"].startswith("D"):
        depth = decode_depth(raw, meta)
        report(meta["label"], depth)
        img = depth
        if args.flip:
            img = img[::-1]
        img = np.nan_to_num(img, nan=1.0, posinf=1.0, neginf=0.0)
        arr = np.clip(img * 65535.0, 0, 65535).astype(np.uint16)
        Image.fromarray(arr, mode="I;16").save(out)
    else:
        color = decode_color(raw, meta)
        img = color[::-1] if args.flip else color
        Image.fromarray(img[:, :, :3], mode="RGB").save(out)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
