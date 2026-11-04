#!/usr/bin/env python3
"""Proofs for the body yaw: the body faces the direction the ball rolls, and it is
not a copy of the head.

Read out of two runs of tools/steve/body_shots.sh, paired line by line -- once a
second the engine prints

  at (x, y, z) yaw <head> pitch <p>     -- where the ball is, where the head looks
  state: ... body yaw <body> want <want> speed <v>   -- where the body is aimed,
                                          what the motion asked for, how fast

The claims:

  * the ball really rolled, and its heading really changed (otherwise every
    comparison below would pass on a ball standing still);
  * body yaw == want in every sample -- what the body is aimed at is the heading
    it was asked for, not a leftover;
  * want is the direction of travel, recomputed here from the logged positions:
    the engine is not the only source for that number;
  * with the head held 28.6 deg off to the side, the body still points along the
    path, so the body is reading the motion and not the head;
  * with following switched off (BALLANCE_MC_STEVE_BODY=0) the body stays on the
    head's yaw, which is the fallback every screenshot run relies on.
"""
import math
import os
import re
import sys

POSE = re.compile(r"mc-steve\] at \(([-\d.]+), ([-\d.]+), ([-\d.]+)\) yaw ([-+\d.]+) pitch")
STATE = re.compile(r"state: .*body yaw ([-+\d.]+) want ([-+\d.]+) speed ([-+\d.]+)")
HEAD_TURNED = -28.6      # the mouse burst in body_shots.sh
HEAD_TOL = 0.3


def wrap(deg):
    d = math.fmod(deg + 180.0, 360.0)
    return d + 360.0 - 180.0 if d < 0.0 else d - 180.0


def samples(path):
    """[(position, head yaw, body yaw, want, speed)] in log order."""
    rows = []
    pos = head = None
    for line in open(path, encoding="utf-8", errors="replace"):
        m = POSE.search(line)
        if m:
            pos = tuple(float(v) for v in m.group(1, 2, 3))
            head = float(m.group(4))
            continue
        m = STATE.search(line)
        if m:
            rows.append((pos, head, float(m.group(1)), float(m.group(2)), float(m.group(3))))
    return rows


def main(outdir):
    travel = samples(os.path.join(outdir, "travel.log"))
    off = samples(os.path.join(outdir, "off.log"))
    checks = []

    def want(name, ok, got):
        checks.append((name, ok, got))

    moving = [r for r in travel if r[4] > 0.5]
    want("the ball really rolled",
         len(moving) >= 20 and max(r[4] for r in travel) >= 5.0,
         "%d moving sample(s), max speed %.1f" % (len(moving), max(r[4] for r in travel)))

    spread = max(abs(wrap(a[3] - b[3])) for a in moving for b in moving)
    want("its heading really changed", spread >= 120.0, "spread %.0f deg" % spread)

    worst = max(abs(wrap(r[2] - r[3])) for r in moving)
    want("body yaw is the heading it was asked for", worst <= 15.0,
         "worst %.1f deg over %d sample(s)" % (worst, len(moving)))

    # Independent check of "want": the direction from one logged position to the
    # next, against the heading the engine asked the body for at those moments.
    # A one-second chord cuts the corner of a curving path, so the bar is a median
    # well under the 90 deg a random pairing would average, not an exact match.
    errs = []
    for a, b in zip(travel, travel[1:]):
        if a[0] is None or b[0] is None:
            continue
        dx = b[0][0] - a[0][0]
        dz = b[0][2] - a[0][2]
        if math.hypot(dx, dz) < 1.0:
            continue
        chord = math.degrees(math.atan2(dx, dz))
        sx = math.sin(math.radians(a[3])) + math.sin(math.radians(b[3]))
        sz = math.cos(math.radians(a[3])) + math.cos(math.radians(b[3]))
        errs.append(abs(wrap(chord - math.degrees(math.atan2(sx, sz)))))
    errs.sort()
    median = errs[len(errs) // 2] if errs else 180.0
    want("want matches the path the ball actually took", median <= 30.0,
         "median %.1f deg over %d chord(s)" % (median, len(errs)))

    turned = [r for r in moving if r[1] is not None and abs(r[1] - HEAD_TURNED) <= HEAD_TOL]
    away = [r for r in turned if abs(wrap(r[2] - r[1])) >= 20.0]
    want("body ignores the turned head",
         len(turned) >= 5 and len(away) >= 0.6 * len(turned),
         "%d of %d sample(s) >= 20 deg away" % (len(away), len(turned)))

    # With following switched off the engine zeroes its speed field on purpose (there
    # is no motion it is willing to follow), so "did the ball move" has to come from
    # the logged positions, and the heading spread from the chords between them.
    off_chords = []
    for a, b in zip(off, off[1:]):
        if a[0] is None or b[0] is None:
            continue
        dx = b[0][0] - a[0][0]
        dz = b[0][2] - a[0][2]
        if math.hypot(dx, dz) > 1.0:
            off_chords.append(math.degrees(math.atan2(dx, dz)))
    off_glued = all(abs(wrap(r[2] - r[1])) <= 0.2 for r in off)
    off_spread = max(abs(wrap(a - b)) for a in off_chords for b in off_chords) if off_chords else 0.0
    want("following off keeps the body on the head's yaw",
         len(off_chords) >= 5 and off_glued and off_spread >= 60.0,
         "%d chord(s), heading spread %.0f deg" % (len(off_chords), off_spread))

    bad = 0
    for name, ok, got in checks:
        shown = "-" if got is None else got
        print("%-4s %-46s %s" % ("PASS" if ok else "FAIL", name, shown))
        bad += 0 if ok else 1
    print("PASS" if not bad else "%d FAILED" % bad)
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
