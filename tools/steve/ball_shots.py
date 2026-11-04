#!/usr/bin/env python3
"""Check the "Steve is the ball" frames.

Two claims, and neither of them needs the two runs to match frame for frame
(they cannot: the ball is a physics body and the clock is wall time):

  hero3p  Steve is drawn while he rides the player ball. The marker is his shirt
          teal (0,168,170): measured at 0 pixels in every frame without him --
          the pillar shots' control frame, and the first person frames here --
          and 2-4k pixels with him. Skin brown is useless as a marker because
          the level's own track is full of it (62k pixels with Steve absent).

  fp      Two frames 300 render calls apart. Steve must not be drawn (the eye is
          inside his head) and the view must have moved, which is what "the
          camera rides the ball" looks like from outside.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

SHIRT = (0, 168, 170)


def frames(tag_dir):
    out = []
    for meta in sorted(Path(tag_dir).glob("native-color-*.json")):
        info = json.loads(meta.read_text())
        raw = meta.with_suffix(".bin").read_bytes()
        a = np.frombuffer(raw, dtype=np.uint8).reshape(info["height"], info["width"], 4)
        out.append(a[:, :, 2::-1].astype(np.int16))   # BGRA -> RGB
    return out


def save(rgb, path, scale=1):
    img = Image.fromarray(rgb.astype(np.uint8), "RGB")
    if scale != 1:
        img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    img.save(path)


def shirt_mask(rgb, tol=0.18):
    norm = rgb.astype(np.float32)
    norm /= np.maximum(norm.max(axis=2, keepdims=True), 1.0)
    want = np.array(SHIRT, dtype=np.float32)
    want /= float(want.max())
    return np.abs(norm - want).max(axis=2) < tol


def main(outdir):
    out = Path(outdir)
    ok = True

    hero = frames(out / "hero3p")[0]
    save(hero, out / "10-steve-is-the-ball.png")
    mask = shirt_mask(hero)
    count = int(mask.sum())
    print("third person: %d shirt-teal pixels (0 = Steve absent, so this is him)" % count)
    if count > 500:
        ys, xs = np.nonzero(mask)
        print("  his footprint: x[%d..%d] y[%d..%d] of %dx%d"
              % (xs.min(), xs.max(), ys.min(), ys.max(), hero.shape[1], hero.shape[0]))
        pad = 24
        crop = hero[max(0, ys.min() - pad):ys.max() + pad, max(0, xs.min() - pad):xs.max() + pad]
        save(crop, out / "12-steve-zoom.png", scale=4)
    else:
        print("  FAIL: Steve is not in the third person frame")
        ok = False

    fp = frames(out / "fp")
    print("first person: %d frame(s)" % len(fp))
    for i, f in enumerate(fp):
        save(f, out / ("11-first-person-from-the-ball-%d.png" % i))
        print("  frame %d: %d shirt-teal pixels (must be 0: the eye is in his head)"
              % (i, int(shirt_mask(f).sum())))
        if int(shirt_mask(f).sum()) > 200:
            print("  FAIL: the avatar is drawn in front of his own eyes")
            ok = False
    if len(fp) < 2:
        print("  FAIL: need two frames to show the camera moving")
        ok = False
    else:
        moved = int((np.abs(fp[0] - fp[1]).max(axis=2) > 8).sum())
        print("  %d pixels differ between the two frames (the eye rode the ball)" % moved)
        if moved < 15000:
            print("  FAIL: the view barely changed; the camera is not following the ball")
            ok = False

    print("PASS" if ok else "FAIL")
    print("images in %s" % out)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/ball-shots"))
