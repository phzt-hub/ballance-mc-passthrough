#!/usr/bin/env bash
# Does the mouse change what the camera draws -- and does each axis do its own job?
#
#   ./look_shots.sh [outdir]
#
# Five first-person runs from the same pose on Level_01, Steve parked at a fixed
# position so the world itself is frame-deterministic (verified earlier: two
# identical runs give 0 differing pixels):
#
#   control    no pointer motion
#   yawonly    10 x 20px right    -> yaw -28.6 deg, pitch 0
#   pitchonly  6 x 10px up        -> pitch +8.6 deg, yaw 0
#   turned     both               -> yaw -28.6, pitch +8.6
#   turned2    the same input again -- must be pixel-identical to "turned"
#
# "yawonly" and "pitchonly" exist so neither axis can hide behind the other: a
# single "turned" frame that differs from the control would also pass if only
# one of the two angles had reached the camera.
set -o pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$1"; [ -n "$OUT" ] || OUT="$ROOT/run/look-shots"
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
LEVEL="3D Entities/Level/Level_01.NMO"
POS="-160,16.19,-366"
FRAME=420
[ -n "$DISPLAY" ] || DISPLAY=:99; export DISPLAY

shoot() {
    local tag=$1 mode=$2
    rm -rf "$OUT/$tag"; mkdir -p "$OUT/$tag"
    echo "== $tag"
    env BALLANCE_POC_ISOLATE=1 BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_LEVEL=1 BALLANCE_MC_STEVE_FP=1 BALLANCE_MC_STEVE_YAW=0 BALLANCE_MC_STEVE_SCALE=0.25 BALLANCE_MC_STEVE_POS=$POS BALLANCE_MC_STEVE_DEBUG=1 OUT="$OUT/$tag" RUN_SECONDS=16 FRAMES=$FRAME "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo --level "$LEVEL" > "$OUT/$tag.log" 2>&1 &
    local runner=$!
    if [ "$mode" != "none" ]; then
        for _ in $(seq 1 60); do grep -q "mc-steve. built" "$OUT/$tag.log" 2>/dev/null && break; sleep 0.5; done
        sleep 3
        local w; w=$(xdotool search --onlyvisible --classname Player 2>/dev/null | tail -1)
        if [ -n "$w" ]; then
            xdotool windowactivate --sync "$w" 2>/dev/null
            sleep 0.3
            case "$mode" in
                yaw|both)   for _ in $(seq 1 10); do xdotool mousemove_relative -- 20 0; sleep 0.05; done ;;
            esac
            case "$mode" in
                pitch|both) for _ in $(seq 1 6); do xdotool mousemove_relative -- 0 -10; sleep 0.05; done ;;
            esac
        fi
    fi
    wait "$runner" 2>/dev/null
    grep -a "mc-steve. at (" "$OUT/$tag.log" | tail -1
}

shoot control   none
shoot yawonly   yaw
shoot pitchonly pitch
shoot turned    both
shoot turned2   both

python3 "$ROOT/tools/steve/look_shots_check.py" "$OUT" $FRAME
