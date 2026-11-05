#!/usr/bin/env bash
# Drive the settings panel with a real pointer and a real keyboard.
#
#   tools/panel/panel-input-test.sh
#
# A click that changes nothing can mean three different things, and only one of
# them is a bug in the panel:
#
#   * the hit test never saw the press   -> no "click at" line in the log at all
#   * the press missed every rect        -> "click at ... row -1"
#   * the press hit the row, nothing else moved
#
# So the panel can log the rects it uses (BALLANCE_MC_PANEL_LAYOUT=1) and every
# press with its coordinates. This script aims at the centres of those very
# rects, which checks the mouse coordinate space against the drawing space
# instead of trusting both. The pointer is moved with xdotool and the button is
# held across several frames: a press and release inside one frame is invisible
# to a state poll, because SDL delivers both events before the frame reads
# IsMouseButtonDown.
#
# The keyboard half is the same question for F1 and Escape, which are read from
# the frame's key buffer for exactly that reason.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${OUT:-$ROOT/run/panel-input}
GAME=${GAME:-$ROOT/run/game}
DISPLAY=${DISPLAY:-:99}
RUN_SECONDS=${RUN_SECONDS:-50}
export DISPLAY

rm -rf "$OUT"; mkdir -p "$OUT"
# Start from the shipped defaults, so "the ini still says 0" after a click is
# information rather than a leftover from an earlier run.
rm -f "$GAME/Bin/Minecraft.ini"

BALLANCE_MC_PANEL_TEST=1 BALLANCE_MC_PANEL_LAYOUT=1 \
FRAMES='' OUT="$OUT/dumps" RUN_SECONDS="$RUN_SECONDS" \
    "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo \
    --level "3D Entities/Level/Level_01.NMO" >"$OUT/panel.log" 2>&1 &
RUNNER=$!
trap 'kill "$RUNNER" 2>/dev/null || true; pkill -f "run/game/Bin/.?/Player" 2>/dev/null || true' EXIT

python3 - "$OUT/panel.log" "$OUT/ini-at-end.txt" <<'PYEOF'
import os, re, subprocess, sys, time

log_path, ini_snapshot = sys.argv[1], sys.argv[2]
INI = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(log_path)), "..", "game", "Bin", "Minecraft.ini"))


def log():
    try:
        return open(log_path, encoding="utf-8", errors="replace").read()
    except FileNotFoundError:
        return ""


def wait_for(marker, timeout=90.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if marker in log():
            return True
        time.sleep(0.1)
    return False


def xdo(*args):
    return subprocess.run(["xdotool", *args], capture_output=True, text=True)


def find_window():
    # Any visible window at the game's resolution: the title belongs to the
    # engine, the size is the one thing this test already pins.
    for wid in xdo("search", "--onlyvisible", "--name", ".").stdout.split():
        geo = xdo("getwindowgeometry", "--shell", wid).stdout
        fields = dict(line.split("=", 1) for line in geo.strip().splitlines() if "=" in line)
        if fields.get("WIDTH") == "640" and fields.get("HEIGHT") == "480":
            return wid, int(fields["X"]), int(fields["Y"])
    raise SystemExit("panel-input: no 640x480 window on DISPLAY=%s" % os.environ.get("DISPLAY"))


def rects():
    text = log()
    rows, boxes = {}, {}
    for m in re.finditer(r"layout: row (\d+) (\w+) (bool|number) ([\d.]+),([\d.]+)\.\.([\d.]+),([\d.]+)", text):
        rows[int(m.group(1))] = (m.group(2), m.group(3), tuple(float(m.group(i)) for i in range(4, 8)))
    for m in re.finditer(
        r"layout: row (\d+) minus ([\d.]+),([\d.]+)\.\.([\d.]+),([\d.]+) plus ([\d.]+),([\d.]+)\.\.([\d.]+),([\d.]+)",
        text,
    ):
        box = tuple(float(m.group(i)) for i in range(2, 10))
        boxes[int(m.group(1))] = {"minus": box[:4], "plus": box[4:]}
    panel = re.search(r"layout: window \d+x\d+ panel ([\d.]+),([\d.]+)\.\.([\d.]+),([\d.]+)", text)
    reset = re.search(r"layout: reset ([\d.]+),([\d.]+)\.\.([\d.]+),([\d.]+)", text)
    return (
        rows,
        boxes,
        tuple(float(panel.group(i)) for i in range(1, 5)) if panel else None,
        tuple(float(reset.group(i)) for i in range(1, 5)) if reset else None,
    )


def centre(rect):
    return ((rect[0] + rect[2]) * 0.5, (rect[1] + rect[3]) * 0.5)


def echo_since(needle_count, needles):
    lines = log().splitlines()
    hits = [line for line in lines if any(n in line for n in needles)]
    for line in hits[needle_count:]:
        print("   " + line.split("] ", 1)[-1])
    return len(hits)


def click(origin, x, y, label):
    presses_before = log().count("[mc-panel] click at")
    xdo("mousemove", str(origin[0] + int(round(x))), str(origin[1] + int(round(y))))
    time.sleep(0.15)  # let the motion event become the panel's pointer position
    xdo("mousedown", "1")
    time.sleep(0.30)  # several frames with the button down
    xdo("mouseup", "1")
    time.sleep(0.45)
    print("-- click %s: window %d,%d" % (label, round(x), round(y)))
    lines = log().splitlines()
    presses = [i for i, line in enumerate(lines) if line.startswith("[mc-panel] click at")]
    if len(presses) <= presses_before:
        print("   !! no press reached the panel")
        return
    for line in lines[presses[-1]:]:
        print("   " + line.split("] ", 1)[-1])


def press(key, label, hold=0.15):
    opened, closed = log().count("panel opened"), log().count("panel closed")
    xdo("keydown", key)
    time.sleep(hold)
    xdo("keyup", key)
    time.sleep(0.8)
    print("-- key %s (%s)" % (key, label))
    after_opened, after_closed = log().count("panel opened"), log().count("panel closed")
    if (after_opened, after_closed) == (opened, closed):
        print("   !! nothing happened: the key never reached the panel")
    else:
        print("   panel %s" % ("opened" if after_opened > opened else "closed"))


if not wait_for("layout: reset"):
    print("no layout in the log: the panel never opened")
    raise SystemExit(1)

wid, wx, wy = find_window()
print("window %s at %d,%d" % (wid, wx, wy))
rows, boxes, panel, reset = rects()
if not rows or panel is None or reset is None:
    raise SystemExit("no rects in the log")
origin = (wx, wy)

print("== clicks ==")
click(origin, *centre(rows[0][2]), "row 0 (Steve) -> off")
click(origin, *centre(rows[0][2]), "row 0 (Steve) -> on")
click(origin, *centre(rows[4][2]), "row 4 (HideBall) -> on")
click(origin, *centre(boxes[5]["plus"]), "row 5 (Scale) plus")
click(origin, *centre(boxes[5]["minus"]), "row 5 (Scale) minus")
click(origin, panel[0] + 3, (rows[1][2][1] + rows[1][2][3]) * 0.5, "panel edge, expected miss")
click(origin, *centre(reset), "Restore defaults")

print("== keyboard ==")
press("F1", "close")
# Closed means closed: the same press that toggled a row a moment ago must now
# reach nothing at all, not the row underneath the closed panel.
before_presses = log().count("[mc-panel] click at")
before_ini = open(INI, encoding="utf-8", errors="replace").read() if os.path.exists(INI) else ""
click(origin, *centre(rows[0][2]), "row 0 while closed, expected nothing")
print("   presses while closed: %d (expected 0)" % (log().count("[mc-panel] click at") - before_presses))
print("   ini unchanged: %s" % (before_ini == (open(INI, encoding="utf-8", errors="replace").read() if os.path.exists(INI) else "")))
press("F1", "reopen")
press("Escape", "close")
time.sleep(0.5)

print("== saved %d time(s)" % log().count("[mc-panel] saved "))
open(ini_snapshot, "w", encoding="utf-8").write(
    open(INI, encoding="utf-8", errors="replace").read() if os.path.exists(INI) else "<no ini>"
)
print("== ini ->", ini_snapshot)
PYEOF

echo "== log: $OUT/panel.log"
grep -E '^\[mc-panel\] (panel|click at|restored defaults|saved )|^\[mc-steve\] (steve|relative mouse)' "$OUT/panel.log" || true
echo "== ini at exit"
grep -vE '^;|^$' "$OUT/ini-at-end.txt" || true
