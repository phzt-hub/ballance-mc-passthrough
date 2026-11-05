#!/usr/bin/env bash
# Minecraft HUD and the missing Steve body, as pictures.
#
#   ./render-hud-shots.sh [outdir]
#
# Five runs, chosen so each claim has a control that differs in one variable:
#
#   none   parked camera, no HUD, Steve off      pristine world  (the control)
#   body   parked camera, no HUD, Steve=1        his model is still drawable
#   hud    parked camera, HUD on                 HUD without any Steve
#   slot   as hud, but slot 4 held               the highlight moves
#   fp     first person, HUD on                  the arm appears
#   scale  as hud, HudScale=2                    the HudScale row works
#
# Why a parked camera for the first four: comparing "body on" and "body off"
# needs two frames from the same eye, or every pixel differs and the diff proves
# nothing. The parked eye comes from BALLANCE_POC_CAM (tools/levelboot), the same
# instrument the earlier close-ups used.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-$ROOT/run/hud-shots}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)

LEVEL="3D Entities/Level/Level_01.NMO"
POS="-160,16.19,-366"
# Above the slab's top face, looking at the parked Steve.
PARKED=${PARKED:--160,26,-347,-160,17,-366}
export DISPLAY=${DISPLAY:-:99}

shoot() {
    local tag=$1 envs=$2
    local dir="$OUT/$tag"
    rm -rf "$dir"; mkdir -p "$dir"
    echo "== $tag"
    # A Minecraft.ini left by an earlier run would out-vote the environment for any
    # row this run does not pin, and "Steve off" is exactly such a row.
    rm -f "$ROOT/run/game/Bin/Minecraft.ini"
    env OUT="$dir" RUN_SECONDS=${RUN_SECONDS:-14} FRAMES= BALLANCE_POC_ISOLATE=1 $envs \
        "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo --level "$LEVEL" \
        > "$OUT/$tag.log" 2>&1 &
    local runner=$!

    local wid=""
    for _ in $(seq 1 80); do
        wid=$(xdotool search --onlyvisible --classname Player 2>/dev/null | tail -1)
        [ -n "$wid" ] && break
        sleep 0.25
    done
    if [ -z "$wid" ]; then
        echo "!! no window for $tag" >&2
        wait "$runner" 2>/dev/null
        return 1
    fi
    sleep 1.5
    xdotool windowmove "$wid" 0 0 2>/dev/null
    eval "$(xdotool getwindowgeometry --shell "$wid")"
    sleep 2
    # Three frames a second apart: the HUD is a static overlay, so identical
    # frames are part of the proof rather than noise to be averaged away.
    for i in 1 2 3; do
        ffmpeg -loglevel error -f x11grab -video_size "${WIDTH}x${HEIGHT}" \
            -i "${DISPLAY}+${X},${Y}" -frames:v 1 -y "$dir/shot$i.png" 2>/dev/null
        sleep 0.8
    done
    echo "$WIDTH $HEIGHT" > "$dir/size.txt"
    wait "$runner" 2>/dev/null
}

shoot none "BALLANCE_POC_CAM=$PARKED BALLANCE_MC_HUD=0 BALLANCE_MC_STEVE=0 BALLANCE_MC_STEVE_FP=0"
shoot body "BALLANCE_POC_CAM=$PARKED BALLANCE_MC_HUD=0 BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_FP=0 BALLANCE_MC_STEVE_YAW=0 BALLANCE_MC_STEVE_POS=$POS"
shoot hud  "BALLANCE_POC_CAM=$PARKED BALLANCE_MC_HUD=1 BALLANCE_MC_STEVE=0 BALLANCE_MC_STEVE_FP=0"
shoot slot "BALLANCE_POC_CAM=$PARKED BALLANCE_MC_HUD=1 BALLANCE_MC_STEVE=0 BALLANCE_MC_STEVE_FP=0 BALLANCE_MC_HUD_SLOT=4"
shoot fp   "BALLANCE_MC_HUD=1 BALLANCE_MC_STEVE_FP=1 BALLANCE_MC_STEVE_LEVEL=1 BALLANCE_MC_STEVE_YAW=0 BALLANCE_MC_STEVE_POS=$POS"
shoot scale "BALLANCE_POC_CAM=$PARKED BALLANCE_MC_HUD=1 BALLANCE_MC_STEVE=0 BALLANCE_MC_STEVE_FP=0 BALLANCE_MC_HUD_SCALE=2"

python3 "$ROOT/tools/hud/hud_check.py" "$OUT"
