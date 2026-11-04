#!/usr/bin/env python3
"""Assert that mouse motion reached Steve and turned his head.

Numbers, not prose: sens 0.0025 rad/px, so 200px right must be -28.6 deg of yaw
and 60px up must be +8.6 deg of pitch.

Read as *tick deltas*, not as an absolute pose: DebugLog prints the pose and then
that second's mouse sum, so the pose on the same line as a delta has already been
turned. The baseline is therefore the pose of the tick before it.
"""
import math
import re
import sys

SENS = 0.0025
TOLERANCE = 0.6
WANT_YAW = -math.degrees(200 * SENS)
WANT_PITCH = math.degrees(60 * SENS)

POSE = re.compile(r"mc-steve\] at \([^)]*\) yaw ([-\d.]+) pitch ([-\d.]+)")
LOOK = re.compile(r"look: mouse d\(([-+\d.]+), ([-+\d.]+)\)")
MOTION = re.compile(r"input-debug\] SDL_EVENT_MOUSE_MOTION")


def ticks(text):
    """[(yaw, pitch, dx, dy)] in the order the game printed them."""
    out, pose = [], None
    for line in text.splitlines():
        m = POSE.search(line)
        if m:
            pose = (float(m.group(1)), float(m.group(2)))
            continue
        m = LOOK.search(line)
        if m and pose is not None:
            out.append((pose[0], pose[1], float(m.group(1)), float(m.group(2))))
    return out


def main(path):
    text = open(path, "rb").read().decode("utf-8", "replace")
    rows = ticks(text)
    motions = len(MOTION.findall(text))
    if not rows:
        print("FAIL: no pose line -- Steve never built")
        return 1

    yaw_turn = pitch_turn = None
    for i in range(1, len(rows)):
        _, _, dx, dy = rows[i]
        if dx > 0 and yaw_turn is None:
            yaw_turn = rows[i][0] - rows[i - 1][0]
        if dy != 0 and pitch_turn is None:
            pitch_turn = rows[i][1] - rows[i - 1][1]
    checks = [
        ("motion events reached the input manager", motions >= 15, motions),
        ("dx reached Steve and turned yaw by %.1f deg" % WANT_YAW,
         yaw_turn is not None and abs(yaw_turn - WANT_YAW) <= TOLERANCE, yaw_turn),
        ("dy reached Steve and turned pitch by %.1f deg" % WANT_PITCH,
         pitch_turn is not None and abs(pitch_turn - WANT_PITCH) <= TOLERANCE, pitch_turn),
        ("pitch stays inside +-85 deg", abs(rows[-1][1]) <= 85.0, rows[-1][1]),
    ]
    bad = 0
    for name, ok, got in checks:
        print("%-4s %-48s %s" % ("PASS" if ok else "FAIL", name,
                                 "-" if got is None else "%.1f" % got if isinstance(got, float) else got))
        bad += 0 if ok else 1
    print("PASS" if not bad else "%d FAILED" % bad)
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
