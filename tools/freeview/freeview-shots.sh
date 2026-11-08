#!/usr/bin/env bash
# Free view (F) acceptance: the orbit turns, the picture follows it, and the
# ball keeps steering itself.
#
#   ./freeview-shots.sh [outdir]        default run/freeview
#
# Two runs of level 8, entered through the menu the way a player does. The first
# holds Up and does nothing else; the second presses F, turns the view 90 degrees
# right and 17 degrees up, gives the level camera back, and then holds Up for
# exactly as long. The ball ends up in the same place either way -- that is the
# promise: the free view changes what the player sees, not what the game does.
#
# Then freeview_check.py judges the six claims, from the shared camera object and
# from the frames, never from the log alone.
#
# Needed: a running X server on $DISPLAY (Xvfb :99 here), xdotool, ffmpeg.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$1"; [ -n "$OUT" ] || OUT="$ROOT/run/freeview"
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
if [ -z "$DISPLAY" ]; then DISPLAY=:99; export DISPLAY; fi

RUNNER=""

start_run() {
    local tag=$1
    echo "== $tag: starting"
    env BALLANCE_MC_PASSTHROUGH=1 BALLANCE_MC_CAMERA=1 \
        OUT="$OUT/depth-$tag" RUN_SECONDS=150 FRAMES='' \
        "$ROOT/tools/poc1/run-poc1.sh" > "$OUT/run-$tag.log" 2>&1 &
    RUNNER=$!

    for _ in $(seq 1 60); do xdotool search --name Ballance >/dev/null 2>&1 && break; sleep 0.5; done
    sleep 3
    "$ROOT/tools/passthrough/enter-level-8.sh" 5 || { echo "!! $tag: menu click failed" >&2; return 1; }
    for _ in $(seq 1 80); do
        grep -qa 'camera=InGameCam' "$OUT/run-$tag.log" && break
        sleep 0.5
    done
    grep -qa 'camera=InGameCam' "$OUT/run-$tag.log" || { echo "!! $tag: no gameplay camera" >&2; return 1; }
    sleep 2

    local win
    win=$(xdotool search --name Ballance 2>/dev/null | tail -1)
    xdotool windowactivate --sync "$win" 2>/dev/null
    sleep 0.4
    # Park the pointer in the middle: the game's own cursor position is where a
    # later relative move starts, and the two runs must not differ there.
    xdotool mousemove --window "$win" 320 240
    sleep 0.4
}

stop_run() {
    [ -n "$RUNNER" ] && kill "$RUNNER" 2>/dev/null
    # run-poc1.sh starts the game under timeout(1): killing the script leaves the
    # game alive and publishing, and two publishers make every sample a coin flip.
    pkill -x Player 2>/dev/null
    [ -n "$RUNNER" ] && wait "$RUNNER" 2>/dev/null
    RUNNER=""
    sleep 1
}

status=0
start_run control || status=1
if [ "$status" = 0 ]; then
    python3 "$ROOT/tools/freeview/drive.py" --out "$OUT/control.csv" --mode control || status=1
fi
stop_run

if [ "$status" = 0 ]; then
    start_run orbit || status=1
fi
if [ "$status" = 0 ]; then
    python3 "$ROOT/tools/freeview/drive.py" --out "$OUT/orbit.csv" --mode orbit --shots "$OUT" || status=1
fi
stop_run

cat "$OUT/run-control.log" "$OUT/run-orbit.log" > "$OUT/run.log" 2>/dev/null

if [ "$status" != 0 ]; then
    echo "!! a run failed to start; see $OUT/run-*.log" >&2
    exit 1
fi

python3 "$ROOT/tools/freeview/freeview_check.py" "$OUT"
