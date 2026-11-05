#!/usr/bin/env python3
"""Check what render-view-shots.sh captured.

What is asserted here is only what a number can carry:

  the arm      first person draws it (its flat sleeve/hand colours), third person
               draws none of it, and F5 moves between the two views.
  the rest     reported as measurements, not assertions.

Why the ball is not asserted: level 01's start pad animates for the first seconds
of the level, so two runs of the same script differ by ~6k pixels whatever the
switches say -- five times the size of the ball. A pixel difference in that window
is timing, not evidence. The ball's half of the rule therefore rests on the rig's
own log ("ball hidden (first person)"), and that needs a ball the rig can find:
in a level started from the menu there is no I_Ball_* object at all (only Ball_*
decorations), which is an open gap -- see docs/steve-in-ballance.md.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

SLEEVE = (0, 175, 175)
SKIN = (198, 141, 107)


def load(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.int16)


def changed(a, b, tol=8):
    return np.abs(a - b).max(axis=2) > tol


def exact(img, colour, tol=2):
    lo = np.array(colour, dtype=np.int16) - tol
    hi = np.array(colour, dtype=np.int16) + tol
    return int(((img >= lo) & (img <= hi)).all(axis=2).sum())


def bbox(mask):
    ys, xs = np.nonzero(mask)
    if not len(xs):
        return None
    return (int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max()))


def main(outdir):
    out = Path(outdir)
    p = lambda *parts: load(out.joinpath(*parts))
    t0, t1 = p("3p-fp", "3p-0.png"), p("3p-fp", "3p-1.png")
    fp0, fp1 = p("3p-fp", "fp-0.png"), p("3p-fp", "fp-1.png")
    back = p("3p-fp", "back-3p.png")
    n0 = p("3p-noball", "noball-0.png")
    b0 = p("3p-body", "body-0.png")

    ok = True

    # 1. First person: the arm, by its own flat colours, in both frames.
    sleeve0, sleeve1, hand = exact(fp0, SLEEVE), exact(fp1, SLEEVE), exact(fp0, SKIN)
    print("fp: sleeve %d / %d px, hand %d px (the arm, a second apart)"
          % (sleeve0, sleeve1, hand))
    ok &= min(sleeve0, sleeve1) > 300 and hand > 200

    # 2. No arm outside first person.
    print("3p: sleeve %d px; back in 3p: sleeve %d px (0 = no arm in third person)"
          % (exact(t0, SLEEVE), exact(back, SLEEVE)))
    ok &= exact(t0, SLEEVE) < 20 and exact(back, SLEEVE) < 20

    # 3. The rig says F5 moved the view, and back.
    log = (out / "3p-fp.log").read_text(errors="replace")
    to_fp = "view key: F5 -> first person" in log
    to_3p = "view key: F5 -> third person" in log
    print("log: F5 -> first person %s, F5 -> third person %s"
          % ("yes" if to_fp else "NO", "yes" if to_3p else "NO"))
    ok &= to_fp and to_3p
    if "ball hidden (first person)" in log:
        print("log: the rig hid the ball in first person")
    else:
        print("log: KNOWN GAP -- the rig never found the ball in this path"
              " (no I_Ball_*), so it could not hide it; see the doc")

    # Measurements, for the record.
    d_ball = int(changed(t0, n0).sum())
    d_steve = int(changed(t0, b0).sum())
    sbox = bbox(changed(t0, b0))
    print("measured: 3p vs ball-hidden %d px, 3p vs body-on %d px%s"
          % (d_ball, d_steve,
             "" if not sbox else " (body-on differs in x[%d..%d] y[%d..%d])" % sbox))
    print("           both are within the ~6k px of run-to-run noise on this"
          " animating pad, so neither is used as evidence")

    print("PASS" if ok else "FAIL")
    print("images in %s" % out)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "run/view-shots"))
