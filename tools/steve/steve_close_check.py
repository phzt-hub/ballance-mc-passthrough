#!/usr/bin/env python3
"""Proofs for the close-up milestone.

  * the frame is repeatable           hero  vs hero2  must be pixel-identical
  * Steve is in the shot              hero  vs hide   diff must be a big blob
  * he is textured and upright        palette rows skin < shirt < pants
  * the track occludes him            parksunk vs parkhide must be identical
  * first person is another camera    fp    vs hide   big diff
"""
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
    return a[:, :, 2::-1].astype(np.int16)     # BGRA -> RGB


def save(rgb, path, scale=1):
    img = Image.fromarray(rgb.astype(np.uint8), "RGB")
    if scale != 1:
        img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    img.save(path)


def changed(control, frame):
    return np.abs(control - frame).max(axis=2) > 8


def main(outdir):
    out = Path(outdir)
    hide, hero, hero2 = load(out / "hide"), load(out / "hero"), load(out / "hero2")
    fp = load(out / "fp")
    parkhide, parkhero, parksunk = (load(out / "parkhide"), load(out / "parkhero"),
                                    load(out / "parksunk"))

    save(hide, out / "20-close-control.png")
    save(hero, out / "21-close-hero.png")
    save(fp, out / "23-close-first-person.png")
    save(parkhide, out / "24-parked-control.png")
    save(parkhero, out / "25-parked-hero.png")

    repeat = int(changed(hero, hero2).sum())
    print("repeat run:      %6d pixels differ (0 = frame is deterministic)" % repeat)

    mask = changed(hide, hero)
    ys, xs = np.nonzero(mask)
    if not len(xs):
        print("FAIL: nothing drawn in the close-up")
        return 1
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    w, h = x1 - x0 + 1, y1 - y0 + 1
    save(hero[max(0, y0 - 8):y1 + 8, max(0, x0 - 8):x1 + 8], out / "22-close-zoom.png", scale=3)
    print("close-up:        %6d pixels, bbox %dx%d at x[%d..%d] y[%d..%d], aspect %.2f (skin is 2.0)"
          % (mask.sum(), w, h, x0, x1, y0, y1, h / float(w)))

    norm = hero[mask].astype(np.float32)
    norm /= np.maximum(norm.max(axis=1, keepdims=True), 1.0)
    hits, order = {}, {}
    for name, color in PALETTE.items():
        want = np.array(color, dtype=np.float32)
        want /= max(float(want.max()), 1.0)
        sel = np.abs(norm - want).max(axis=1) < 0.18
        hits[name] = int(sel.sum())
        if sel.sum() > 100:
            order[name] = float(ys[sel].mean())
    print("palette:         " + ", ".join("%s=%d" % kv for kv in hits.items()))
    print("mean row:        " + ", ".join("%s=%.1f" % kv for kv in order.items()))
    upright = order.get("skin", 1e9) < order.get("shirt", 1e9) < order.get("pants", 1e9)
    print("upright:         %s (skin above shirt above pants)" % ("yes" if upright else "NO"))

    park = int(changed(parkhide, parkhero).sum())
    sunk = int(changed(parkhide, parksunk).sum())
    print("parked hero:     %6d pixels differ from the same view without Steve" % park)
    print("parked sunk:     %6d pixels differ (0 = the slab's depth buffer hides him)" % sunk)

    fp_pixels = int(changed(hide, fp).sum())
    print("first person:    %6d pixels differ from the attached camera" % fp_pixels)

    ok = (mask.sum() > 3000 and repeat == 0 and park > 1000 and sunk == 0
          and all(v > 100 for v in hits.values()) and upright
          and 1.7 < h / float(w) < 2.4 and fp_pixels > 10000)
    print("PASS" if ok else "FAIL")
    print("images in %s" % out)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
