#!/usr/bin/env bash
# Deterministic close-up of Steve standing on Level_01's first slab, plus the
# occlusion proof, plus a repeatability proof.
#
#   ./render-steve-close.sh [outdir]
#
# Two camera rigs, because the two claims need different ones:
#
#   attached   camera rides on Steve (BALLANCE_MC_STEVE_CAMOFF). ~21 units away,
#              he stays framed however far the ball rolls.
#   parked     BALLANCE_POC_CAM, eye above the slab looking down. A parked eye is
#              what makes the "sunk" control legit: sinking Steve must change
#              nothing, because the track's own depth buffer hides him.
#
# Two traps this script exists to avoid, both measured on 2026-11-04:
#   * without "--level" nothing is merged, the level's menu camera stays
#     attached, the frame log says camera=Cam_MenuLevel and Steve is not in shot
#     at all -- every pixel diff then measures the menu, not the model;
#   * an eye below y=20.19 is *inside* the A01_Floor_04 slab, so a parked eye at
#     y=21 sees Steve through the slab's interior and he is simply not visible.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-$ROOT/run/steve-close}
mkdir -p "$OUT"
# Player runs with cwd=$GAME/Bin, so a relative dump directory would land there.
OUT=$(cd "$OUT" && pwd)

LEVEL="3D Entities/Level/Level_01.NMO"
# Feet on A01_Floor_04: the slab's dominant upward face is at y=16.19 (measured
# from the exported OBJ); the level's own ball rests at y=19.4 with a 4-unit
# diameter, i.e. surface 17.4 -- the difference is the slab's rim.
POS=${POS:--160,16.19,-366}
SUNK=${SUNK:--160,-43.81,-366}
# feet + (0, 6, 20): 20.9 units away, above the slab top, on the side Steve
# faces (yaw 0).
CAMOFF=${CAMOFF:-0,6,20}
# Above the slab (top y=20.19) looking down at Steve.
PARKED=${PARKED:--160,26,-347,-160,17,-366}
SCALE=${SCALE:-0.25}

export DISPLAY=${DISPLAY:-:99}

shoot() {
    local tag=$1 env=$2
    rm -rf "$OUT/$tag"; mkdir -p "$OUT/$tag"
    echo "== $tag"
    env OUT="$OUT/$tag" RUN_SECONDS=${RUN_SECONDS:-9} FRAMES=${FRAMES:-360} \
        BALLANCE_POC_ISOLATE=1 $env \
        "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo --level "$LEVEL" \
        > "$OUT/$tag.log" 2>&1
}

ATTACHED="BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_LEVEL=1 BALLANCE_MC_STEVE_YAW=0 BALLANCE_MC_STEVE_SCALE=$SCALE BALLANCE_MC_STEVE_CAMOFF=$CAMOFF"
PARK_ENV="BALLANCE_POC_CAM=$PARKED BALLANCE_MC_STEVE_YAW=0 BALLANCE_MC_STEVE_SCALE=$SCALE"

shoot hide      "$ATTACHED BALLANCE_MC_STEVE_HIDE=1 BALLANCE_MC_STEVE_POS=$POS"
shoot hero      "$ATTACHED BALLANCE_MC_STEVE_POS=$POS"
shoot hero2     "$ATTACHED BALLANCE_MC_STEVE_POS=$POS"
shoot fp        "BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_YAW=0 BALLANCE_MC_STEVE_SCALE=$SCALE BALLANCE_MC_STEVE_FP=1 BALLANCE_MC_STEVE_POS=$POS"
shoot parkhide  "$PARK_ENV BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_HIDE=1 BALLANCE_MC_STEVE_POS=$POS"
shoot parkhero  "$PARK_ENV BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_POS=$POS"
shoot parksunk  "$PARK_ENV BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_POS=$SUNK"

python3 "$ROOT/tools/steve/steve_close_check.py" "$OUT"
