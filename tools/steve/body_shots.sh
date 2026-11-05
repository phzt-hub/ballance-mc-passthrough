#!/usr/bin/env bash
# Does the body face the way the ball is rolling, and does it keep its own yaw
# while the head looks somewhere else?
#
#   ./body_shots.sh [outdir]
#
# Two first-person runs on Level_01, following the real player ball (no
# BALLANCE_MC_STEVE_POS), driven with real arrow-key holds so the ball rolls two
# different ways:
#
#   travel  body yaw must equal the direction of travel, not the head's yaw
#   off     BALLANCE_MC_STEVE_BODY=0 -- the fallback every screenshot run uses,
#           where the body has to stay welded to the head's yaw
#
# The mouse burst at the end of "travel" turns the head 28.6 deg and leaves it
# there while the ball keeps rolling: any body yaw that still matches the motion
# after that is proof the body is not reading the head.
set -o pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$1"; [ -n "$OUT" ] || OUT="$ROOT/run/body-shots"
mkdir -p "$OUT"
LEVEL="3D Entities/Level/Level_01.NMO"

[ -n "$DISPLAY" ] || DISPLAY=:99
export DISPLAY

run_phase() {
    local tag=$1 seconds=$2 extra=$3
    shift 3
    local log="$OUT/$tag.log"
    rm -rf "$OUT/$tag"; mkdir -p "$OUT/$tag"

    env BALLANCE_POC_ISOLATE=1 BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_LEVEL=1 BALLANCE_MC_STEVE_FP=1 BALLANCE_MC_STEVE_YAW=0 \
        BALLANCE_MC_STEVE_SCALE=0.25 BALLANCE_MC_STEVE_DEBUG=1 $extra \
        OUT="$OUT/$tag" RUN_SECONDS="$seconds" FRAMES= \
        "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo --level "$LEVEL" > "$log" 2>&1 &
    local runner=$!

    for _ in $(seq 1 60); do grep -q "mc-steve. built" "$log" 2>/dev/null && break; sleep 0.5; done
    sleep 5
    local w
    w=$(xdotool search --onlyvisible --classname Player 2>/dev/null | tail -1)
    echo "== $tag window: $w"
    if [ -n "$w" ]; then
        eval "$(xdotool getwindowgeometry --shell "$w")"
        xdotool windowactivate --sync "$w" 2>/dev/null
        xdotool mousemove --window "$w" $((WIDTH / 2)) $((HEIGHT / 2))
        sleep 1
    fi

    while [ "$#" -gt 0 ]; do
        case "$1" in
            hold)
                echo "== $tag hold $2 for $3 s $(date +%H:%M:%S)"
                xdotool keydown "$2"; sleep "$3"; xdotool keyup "$2"; sleep 2
                shift 3
                ;;
            mouse)
                echo "== $tag mouse burst $2 px right $(date +%H:%M:%S)"
                local n=$(( $2 / 20 ))
                for _ in $(seq 1 "$n"); do xdotool mousemove_relative -- 20 0; sleep 0.05; done
                sleep 2
                shift 2
                ;;
        esac
    done

    wait "$runner" 2>/dev/null
    echo "== $tag log: $log"
}

run_phase travel 45 "" hold Up 4 hold Left 4 hold Up 3 mouse 200
run_phase off    25 "BALLANCE_MC_STEVE_BODY=0" hold Up 4 hold Left 4

python3 "$ROOT/tools/steve/body_check.py" "$OUT"
