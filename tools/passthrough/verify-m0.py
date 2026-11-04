#!/usr/bin/env python3
"""Check that the Ballance window shows the producer's pattern.

Regenerates the shmfeed pattern in numpy, resizes the captured window to the
mailbox size and reports how many pixels agree. The game's own interface is
drawn on top of the foreign frame, so the check reports the whole frame, the
border band and the centre separately: the border is where a passthrough frame
is unambiguous.
"""

import sys
import numpy as np
from PIL import Image

W, H = 320, 240


def paint(frame: int, w: int = W, h: int = H) -> np.ndarray:
    xs = np.arange(w)[None, :].repeat(h, axis=0)
    ys = np.arange(h)[:, None].repeat(w, axis=1)
    ball_r = h // 10 + 2
    angle = frame * 0.06
    cx = w // 2 + int((w / 3) * np.cos(angle))
    cy = h // 2 + int((h / 3) * np.sin(angle))
    dx, dy = xs - cx, ys - cy
    img = np.zeros((h, w, 3), np.uint8)
    # The mailbox is BGRA8: byte 0 is blue, byte 2 is red.
    img[..., 2] = (xs * 255 // w)           # B
    img[..., 1] = (ys * 255 // h)           # G
    img[..., 0] = (frame * 3) & 0xFF        # R (frame 1 -> 3)
    ball = dx * dx + dy * dy <= ball_r * ball_r
    img[ball] = 255
    cells, bar_h = 16, max(8, h // 10)
    cell_w = w // cells
    for i in range(cells):
        on = 255 if ((frame >> i) & 1) else 0
        img[h - bar_h:, i * cell_w:(i + 1) * cell_w] = on
    m = max(4, w // 32)
    img[:m, :m] = 255                       # white top left
    img[:m, w - m:] = (0, 0, 255)           # blue top right
    img[h - m:, :m] = (255, 0, 0)           # red bottom left
    return img


def load(path):
    return np.asarray(Image.open(path).convert("RGB"))


def score(shot, expect, tol=28):
    resized = np.asarray(Image.fromarray(shot).resize((W, H), Image.NEAREST)).astype(int)
    diff = np.abs(resized - expect.astype(int)).max(axis=2)
    ok = diff <= tol
    band = np.ones((H, W), bool)
    band[H // 4: 3 * H // 4, W // 4: 3 * W // 4] = False
    centre = ~band
    return ok.mean(), ok[band].mean(), ok[centre].mean()


def main():
    expect = paint(1).astype(int)
    for path in sys.argv[1:]:
        try:
            shot = load(path)
        except Exception as exc:
            print(f"{path}: cannot read ({exc})")
            continue
        allm, bandm, centre = score(shot, expect)
        print(f"{path}: match all={allm:.1%} border={bandm:.1%} centre={centre:.1%} "
              f"mean={shot.mean():.1f} shape={shot.shape[1]}x{shot.shape[0]}")


if __name__ == "__main__":
    main()
