#!/usr/bin/env python3
"""Judge a free-view acceptance run: tools/freeview/freeview-shots.sh calls this.

    freeview_check.py OUT

Six things have to hold, and each one fails on its own line so a regression
names itself:

  1. F turns the free view on and off in the log.
  2. Pressing F does not move the picture: the first published pose after it is
     the level camera's pose (the switch's whole promise).
  3. The mouse turns the view: 628 px of yaw is 90 degrees, and the camera stays
     the same distance from the ball, so it orbits instead of drifting.
  4. The two mouse axes stay separate: pitch moves and yaw does not.
  5. F off gives the level camera back.
  6. The ball's heading is the same with and without the free view -- turning
     the view must not steer the ball.
"""
import math
import os
import sys


def load(path):
    rows = []
    with open(path) as f:
        next(f)
        for line in f:
            p = line.strip().split(",")
            rows.append(dict(t=float(p[0]), tag=p[1], ball=tuple(float(x) for x in p[2:5]),
                             eye=tuple(float(x) for x in p[5:8]),
                             fwd=tuple(float(x) for x in p[8:11]), fovy=float(p[11])))
    return rows


def tag(rows, name):
    return [r for r in rows if r["tag"] == name]


def yaw_pitch(fwd):
    yaw = math.degrees(math.atan2(fwd[0], fwd[2]))
    pitch = math.degrees(math.asin(max(-1.0, min(1.0, fwd[1]))))
    return yaw, pitch


def dist(a, b):
    return math.sqrt(sum((a[i] - b[i]) ** 2 for i in range(3)))


def angdiff(a, b):
    d = (a - b + 180.0) % 360.0 - 180.0
    return abs(d)


PASS = []
FAIL = []


def check(name, ok, detail):
    (PASS if ok else FAIL).append(name)
    print("%s  %-34s %s" % ("PASS" if ok else "FAIL", name, detail))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/freeview"
    control = load(os.path.join(out, "control.csv"))
    orbit = load(os.path.join(out, "orbit.csv"))
    log = open(os.path.join(out, "run.log"), errors="replace").read()

    # 1. the toggle reached the camera sync
    on = "free view on" in log
    off = "free view off" in log
    check("toggle logged", on and off,
          "%d on / %d off" % (log.count("free view on"), log.count("free view off")))

    # 2. no jump when F goes down. The level camera is not perfectly still --
    # it eases after the ball -- so the bar is a jump, not a quantum: a switch
    # that snapped to a made-up default would move the eye by tens of units (the
    # orbit radius here is ~41 u), while the camera's own drift over the same
    # second stays under a unit.
    before = tag(orbit, "start")[-1]
    on = tag(orbit, "on")
    jump = max(dist(before["eye"], r["eye"]) for r in on)
    turn = max(angdiff(yaw_pitch(before["fwd"])[0], yaw_pitch(r["fwd"])[0]) for r in on)
    check("F does not move the picture", jump < 1.0 and turn < 1.0,
          "eye moved %.3f u, yaw %.3f deg over the first second" % (jump, turn))

    # 3. the mouse turns the view, round the ball
    yaw0, pitch0 = yaw_pitch(before["fwd"])
    turned = tag(orbit, "yaw")[-1]
    yaw1, pitch1 = yaw_pitch(turned["fwd"])
    radius0 = dist(before["eye"], before["ball"])
    radius1 = dist(turned["eye"], turned["ball"])
    check("yaw turns 90 deg", abs(angdiff(yaw1, yaw0) - 90.0) < 5.0,
          "%.1f -> %.1f" % (yaw0, yaw1))
    check("pitch stays put on a yaw turn", angdiff(pitch1, pitch0) < 1.0,
          "%.1f -> %.1f" % (pitch0, pitch1))
    check("orbit radius holds", abs(radius1 - radius0) < 0.05 * radius0,
          "%.2f -> %.2f u" % (radius0, radius1))
    moved = dist(turned["ball"], before["ball"])
    check("ball does not move while the view turns", moved < 0.3, "%.3f u" % moved)

    # 4. the axes are separate
    pitched = tag(orbit, "pitch")[-1]
    yaw2, pitch2 = yaw_pitch(pitched["fwd"])
    check("pitch turns alone", angdiff(pitch2, pitch1) > 10.0 and angdiff(yaw2, yaw1) < 1.0,
          "pitch %.1f -> %.1f, yaw %.1f -> %.1f" % (pitch1, pitch2, yaw1, yaw2))

    # 5. F off restores the level camera
    back = tag(orbit, "off")[-1]
    restored = angdiff(yaw_pitch(back["fwd"])[0], yaw0) < 1.0
    check("F off restores the level camera", restored,
          "yaw %.1f (level %.1f)" % (yaw_pitch(back["fwd"])[0], yaw0))

    # 6. the ball steers the same either way
    def roll(rows):
        a = [r for r in rows if r["tag"] == "start"][-1]
        b = [r for r in rows if r["tag"] == "after"][-1]
        return b["ball"][0] - a["ball"][0], b["ball"][2] - a["ball"][2]

    cx, cz = roll(control)
    ox, oz = roll(orbit)
    cdist, odist = math.hypot(cx, cz), math.hypot(ox, oz)
    heading = angdiff(math.degrees(math.atan2(ox, oz)), math.degrees(math.atan2(cx, cz)))
    check("ball rolls the same way", cdist > 15 and odist > 15 and heading < 5.0,
          "control (%.1f, %.1f) vs orbit (%.1f, %.1f), heading diff %.1f deg" %
          (cx, cz, ox, oz, heading))

    print("\n%d passed, %d failed" % (len(PASS), len(FAIL)))
    return 1 if FAIL else 0


sys.exit(main())
