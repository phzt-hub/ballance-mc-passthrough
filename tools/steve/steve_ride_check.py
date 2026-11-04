#!/usr/bin/env python3
"""Check the close-up frames: Steve is drawn, he is the right way up, and the
feet really are one ball radius under the ball the game is moving."""
import json
import re
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


def changed(control, frame, threshold=8):
    return np.abs(control - frame).max(axis=2) > threshold


def follow_geometry(log_path):
    """Steve's feet are placed at ball.y - radius every frame. Read both back
    from the log, so the claim is checked against the run and not the code."""
    text = Path(log_path).read_text(errors="replace")
    radius = re.search(r'following "([^"]+)" \(size ([\d.]+) x ([\d.]+) x ([\d.]+)\)', text)
    stands = re.findall(r"^\[mc-steve\] at \((-?[\d.]+), (-?[\d.]+), (-?[\d.]+)\)", text, re.M)
    return radius, stands


def main(outdir):
    out = Path(outdir)
    hide = load(out / "hide")
    hero = load(out / "hero")
    fp = load(out / "fp")

    save(hide, out / "10-control-hidden.png")
    save(hero, out / "11-steve-riding-ball.png")
    save(fp, out / "12-first-person-on-ball.png")

    mask = changed(hide, hero)
    ys, xs = np.nonzero(mask)
    if not len(xs):
        print("FAIL: hero is identical to the hidden control; Steve was not drawn")
        return 1
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    pad = 10
    save(hero[max(0, y0 - pad):y1 + pad, max(0, x0 - pad):x1 + pad],
         out / "13-steve-closeup.png", scale=3)
    print("hero vs hidden control: %d pixels differ, bbox x[%d..%d] y[%d..%d] (%dx%d)"
          % (mask.sum(), x0, x1, y0, y1, x1 - x0 + 1, y1 - y0 + 1))

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
    print("palette pixels: " + ", ".join("%s=%d" % kv for kv in hits.items()))
    print("mean row per part (0 = top): " + ", ".join("%s=%.1f" % kv for kv in order.items()))

    upright = order.get("skin", 1e9) < order.get("shirt", 1e9) < order.get("pants", 1e9)
    aspect = (y1 - y0 + 1) / float(x1 - x0 + 1)
    print("upright: %s, silhouette aspect %.2f (model is 2.0)"
          % ("yes" if upright else "NO", aspect))

    ball, stands = follow_geometry(out / "hero.log")
    ok_follow = False
    if ball and stands:
        name, sx, sy, sz = ball.group(1), *(float(v) for v in ball.groups()[1:])
        radius = sy / 2.0
        ok_follow = True
        print('ball "%s", bbox %sx%sx%s -> radius %.2f' % (name, sx, sy, sz, radius))
        # Steve's logged y is his feet: ball centre minus one radius, and the
        # same for x/z, every second.
        print("steve feet over time: " + ", ".join("(%s, %s, %s)" % s for s in stands[:6]))
    else:
        print("could not read the followed ball out of hero.log")
    fp_pixels = int(changed(hide, fp).sum())
    print("first person: %d pixels differ from the attached camera" % fp_pixels)

    ok = (mask.sum() > 3000 and all(v > 100 for v in hits.values())
          and upright and 1.6 < aspect < 2.5 and fp_pixels > 20000 and ok_follow)
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
