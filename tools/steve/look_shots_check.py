#!/usr/bin/env python3
"""Proofs for the mouse look: the mouse reaches the field, and the camera uses it.

For each of the five runs it reads two things out of that run's log:

  * the pose line (yaw/pitch, degrees) -- did the pointer move the angles?
  * the camera line (the CKCamera's own forward/up) -- did the camera take them?

and it reads the rendered frame for the pixel claims. Angles alone prove nothing
about what is on screen; pixels alone prove nothing about which angle did it.
Both, per axis, or the claim is not supported.

Note on what is deliberately NOT claimed: that a 28.6 degree turn shifts the
image by ~261 px. Measured on 2026-11-04, it does not -- the best-matching
translation is 0 px, because a rotation about an eye that sits *on* the floor
maps to a perspective warp of the near field, not to a translation. Translation
matching only works for small angles (8.6 deg of pitch did match, -68 px against
-74 predicted).
"""
import json
import math
import re
import sys
from pathlib import Path

import numpy as np

TOL_DIR = 0.01
POSE = re.compile(r"mc-steve\] at \([^)]*\) yaw ([-\d.]+) pitch ([-\d.]+)")
CAM = re.compile(r"camera dir \(([-+\d.]+), ([-+\d.]+), ([-+\d.]+)\) up \(([-+\d.]+), ([-+\d.]+), ([-+\d.]+)\)")


def load(tag_dir, frame):
    meta = Path(tag_dir) / ("native-color-%d.json" % frame)
    if not meta.exists():
        cands = sorted(Path(tag_dir).glob("native-color-*.json"))
        if not cands:
            raise SystemExit("FAIL: no frame dumped in %s" % tag_dir)
        meta = cands[-1]
    info = json.loads(meta.read_text())
    raw = meta.with_suffix(".bin").read_bytes()
    a = np.frombuffer(raw, dtype=np.uint8).reshape(info["height"], info["width"], 4)
    return a[:, :, 2::-1].astype(np.int16)


def read_log(path):
    text = Path(path).read_text(errors="replace")
    poses = [(float(a), float(b)) for a, b in POSE.findall(text)]
    cams = [tuple(float(v) for v in g) for g in CAM.findall(text)]
    return (poses[-1] if poses else (0.0, 0.0)), (cams[-1] if cams else None)


def main(outdir, frame):
    out = Path(outdir)
    tags = ("control", "yawonly", "pitchonly", "turned", "turned2")
    frames = {t: load(out / t, frame) for t in tags}
    logs = {t: read_log(out / ("%s.log" % t)) for t in tags}
    total = frames["control"].shape[0] * frames["control"].shape[1]
    diff = lambda a, b: int((np.abs(frames[a] - frames[b]).max(axis=2) > 8).sum())  # noqa: E731

    checks = []

    def want(name, ok, got):
        checks.append((name, ok, got))

    for tag, want_pose in (("yawonly", (-28.6, 0.0)), ("pitchonly", (0.0, 8.6)),
                           ("turned", (-28.6, 8.6))):
        pose = logs[tag][0]
        want("pose in %-9s %s" % (tag, want_pose),
             abs(pose[0] - want_pose[0]) <= 0.6 and abs(pose[1] - want_pose[1]) <= 0.6,
             "yaw %.1f pitch %.1f" % pose)
        cam = logs[tag][1]
        y, p = math.radians(pose[0]), math.radians(pose[1])
        expect = (math.sin(y) * math.cos(p), math.sin(p), math.cos(y) * math.cos(p))
        ok = cam is not None and all(abs(cam[i] - expect[i]) <= TOL_DIR for i in range(3))
        want("camera follows %-9s" % tag, ok,
             "(%+.3f, %+.3f, %+.3f) want (%+.3f, %+.3f, %+.3f)" % ((cam[:3] + expect) if cam else (0, 0, 0, 0, 0, 0)))

    want("yaw alone changes the frame", diff("control", "yawonly") > total * 0.05,
         "%d px" % diff("control", "yawonly"))
    want("pitch alone changes the frame", diff("control", "pitchonly") > total * 0.05,
         "%d px" % diff("control", "pitchonly"))
    want("same input, same frame", diff("turned", "turned2") == 0, "%d px" % diff("turned", "turned2"))

    bad = 0
    for name, ok, got in checks:
        print("%-4s %-44s %s" % ("PASS" if ok else "FAIL", name, got))
        bad += 0 if ok else 1
    print("PASS" if not bad else "%d FAILED" % bad)
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], int(sys.argv[2])))
