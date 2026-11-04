#!/usr/bin/env python3
"""Proofs for the menu gate: Escape releases the pointer, the head stops turning
while it is released, and both come back without any other input.

Read out of one run's log, in the order the game printed it:

  * "menu up"/"menu gone"  -- the gate changed state
  * "menu key: ..."        -- which key moved it
  * "relative mouse mode"  -- the pointer was really released/recaptured
  * the pose line          -- what the head did in each of the three windows

Windows are split by the gate markers, so every claim covers all pose lines
inside its window rather than one lucky sample.

The optional second argument is pointer.txt from menu_shots.sh: the OS cursor
position before and after each burst. It answers the question the log cannot --
was the same 200 px really delivered to the window? -- because a released
pointer walks the cursor across the screen while a captured one is warped back
to the centre every frame.
"""
import re
import sys

GATE = re.compile(r"menu (up|gone): .*-> pointer (\w+)")
KEY = re.compile(r"menu key: (\w+) -> (\w+)")
MODE = re.compile(r"relative mouse mode (on|off) \(asked (on|off)\)")
POSE = re.compile(r"mc-steve\] at \([^)]*\) yaw ([-+\d.]+) pitch ([-+\d.]+)")
WANT = -28.6
TOL = 0.6
TRAVEL = 200.0          # 10 x 20 px, the burst the head sees
TRAVEL_TOL = 40.0       # a captured pointer is warped back, so it stays put


def windows(text):
    out = {"pre": {"poses": []}, "menu": {"poses": []}, "post": {"poses": []}}
    gate, keys, modes = [], [], []
    stage = "pre"
    for line in text.splitlines():
        m = GATE.search(line)
        if m:
            gate.append((m.group(1), m.group(2)))
            stage = "menu" if m.group(1) == "up" else "post"
            continue
        m = KEY.search(line)
        if m:
            keys.append((m.group(1), m.group(2)))
            continue
        m = MODE.search(line)
        if m:
            modes.append(m.group(1))
            continue
        m = POSE.search(line)
        if m:
            out[stage]["poses"].append(float(m.group(1)))
    return out, gate, keys, modes


def pointer(path):
    """{tag: (dx, dy)} -- how far the OS cursor walked during each burst."""
    moves = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        f = line.split()
        if len(f) != 5:
            continue
        tag = f[0]
        x0, y0, x1, y1 = (float(v) for v in f[1:])
        moves[tag] = (x1 - x0, y1 - y0)
    return moves


def main(path, pointer_path=None):
    text = open(path, encoding="utf-8", errors="replace").read()
    seg, gate, keys, modes = windows(text)
    checks = []

    def want(name, ok, got):
        checks.append((name, ok, got))

    def turned(stage):
        poses = seg[stage]["poses"]
        return None if len(poses) < 2 else poses[-1] - poses[0]

    want("escape released the pointer",
         ("escape", "released") in keys and ("up", "released") in gate,
         "keys %s" % (keys,))
    want("a movement key captured it again",
         ("movement", "captured") in keys and ("gone", "captured") in gate, len(keys))
    # The first "on" is the capture the run starts with, so the pair to look for is
    # the off/on the menu produced -- and it has to be the only off, or the pointer
    # was flapping.
    want("pointer released then recaptured",
         modes.count("off") == 1 and modes[-2:] == ["off", "on"],
         " -> ".join(modes) or "none")
    want("one release and one recapture, no flicker",
         gate == [("up", "released"), ("gone", "captured")], "%d transition(s)" % len(gate))

    pre, menu, post = turned("pre"), turned("menu"), turned("post")
    want("burst 1 turns the head by %.1f deg" % WANT,
         pre is not None and abs(pre - WANT) <= TOL, pre)
    want("burst 2 while released turns it by 0.0 deg", menu == 0.0, menu)
    want("burst 3 turns the head again by %.1f deg" % WANT,
         post is not None and abs(post - WANT) <= TOL, post)

    if pointer_path:
        moves = pointer(pointer_path)
        walked = {tag: abs(dx) + abs(dy) for tag, (dx, dy) in moves.items()}

        def close_to(tag, target):
            got = walked.get(tag)
            return got is not None and abs(got - target) <= TRAVEL_TOL

        # Only the released burst is checked. While the pointer is captured the
        # cursor can still walk *inside* the window (SDL only warps it back when it
        # would leave), so its travel is not a capture signal -- the delta and the
        # yaw are. While released there is no confinement at all, so 200 px of
        # xdotool movement has to show up as 200 px of cursor: that is what says the
        # burst was delivered and not swallowed by a dead window.
        want("the released burst really reached the window",
             close_to("burst2", TRAVEL), walked.get("burst2"))

    bad = 0
    for name, ok, got in checks:
        shown = "-" if got is None else ("%.1f" % got if isinstance(got, float) else got)
        print("%-4s %-46s %s" % ("PASS" if ok else "FAIL", name, shown))
        bad += 0 if ok else 1
    print("PASS" if not bad else "%d FAILED" % bad)
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
