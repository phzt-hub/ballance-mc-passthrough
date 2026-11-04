#!/usr/bin/env python3
"""Turn the raw framebuffer dumps from render-steve-shots.sh into PNGs and check
the three claims the milestone rests on."""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

PALETTE = {"shirt": (0, 168, 170), "skin": (198, 141, 107), "pants": (56, 68, 168)}


def load(tag_dir):
    meta = sorted(Path(tag_dir).glob("native-color-*.json"))[0]
    info = json.loads(meta.read_text())
    raw = meta.with_suffix(".bin").read_bytes()
    a = np.frombuffer(raw, dtype=np.uint8).reshape(info["height"], info["width"], 4)
    return a[:, :, 2::-1].astype(np.int16)   # BGRA -> RGB


def save(rgb, path, scale=1):
    img = Image.fromarray(rgb.astype(np.uint8), "RGB")
    if scale != 1:
        img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    img.save(path)


def changed(control, frame):
    return np.abs(control - frame).max(axis=2) > 8


def main(outdir):
    out = Path(outdir)
    control = load(out / "off")
    hero = load(out / "hero")
    sunk = load(out / "sunk")
    fp = load(out / "fp")

    save(control, out / "00-control.png")
    save(hero, out / "01-steve-on-track.png")
    save(sunk, out / "03-steve-occluded.png")
    save(fp, out / "04-first-person.png")

    mask = changed(control, hero)
    ys, xs = np.nonzero(mask)
    if not len(xs):
        print("FAIL: nothing changed; Steve was not drawn")
        return 1
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    pad = 12
    crop = hero[max(0, y0 - pad):y1 + pad, max(0, x0 - pad):x1 + pad]
    save(crop, out / "02-steve-zoom.png", scale=4)

    print("hero: %d pixels differ from the control, bbox x[%d..%d] y[%d..%d]"
          % (mask.sum(), x0, x1, y0, y1))

    # Steve's own colours have to be in there, otherwise something drew but it
    # was not the skin texture. The level's lighting multiplies the texture
    # down, so compare hue rather than the exact texel value.
    norm = hero[mask].astype(np.float32)
    norm /= np.maximum(norm.max(axis=1, keepdims=True), 1.0)
    hits = []
    for name, color in PALETTE.items():
        want = np.array(color, dtype=np.float32)
        want /= max(float(want.max()), 1.0)
        hits.append("%s=%d" % (name, int((np.abs(norm - want).max(axis=1) < 0.18).sum())))
    print("palette pixels inside the silhouette: " + ", ".join(hits))

    # Steve has to be the right way up: the head (skin) above the shirt above
    # the trousers. Without this the silhouette could pass while the model is
    # upside down or the texture rows are flipped.
    order = {}
    for name, color in PALETTE.items():
        want = np.array(color, dtype=np.float32)
        want /= max(float(want.max()), 1.0)
        sel = np.abs(norm - want).max(axis=1) < 0.18
        if sel.sum() > 100:
            order[name] = float(ys[sel].mean())
    print("mean row of each part (0 = top of frame): "
          + ", ".join("%s=%.1f" % kv for kv in order.items()))

    sunk_pixels = int(changed(control, sunk).sum())
    print("sunk: %d pixels differ from the control (0 = the track occludes him)"
          % sunk_pixels)
    fp_pixels = int(changed(control, fp).sum())
    print("first person: %d pixels differ from the parked camera (the camera moved)"
          % fp_pixels)

    upright = (order.get("skin", 1e9) < order.get("shirt", 1e9) < order.get("pants", 1e9))
    # Perspective is nearly orthographic over 8 units of depth, so the model's
    # own 16x32 texel proportions should survive: about 2.0 tall/wide.
    aspect = (y1 - y0 + 1) / float(x1 - x0 + 1)
    print("upright: %s (skin < shirt < pants), silhouette aspect %.2f (model is 2.0)"
          % ("yes" if upright else "NO", aspect))

    ok = (mask.sum() > 3000 and sunk_pixels == 0 and fp_pixels > 10000
          and upright and 1.6 < aspect < 2.5)
    print("PASS" if ok else "FAIL")
    print("images in %s" % out)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/steve-shots"))
