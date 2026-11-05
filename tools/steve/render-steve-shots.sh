#!/usr/bin/env bash
# Render Steve standing inside a Ballance level, plus the controls that prove
# he is really in the 3D scene.
#
#   ./render-steve-shots.sh [outdir]
#
# Four frames, all from the same parked camera on Level_01 near its start:
#
#   off      Steve disabled                     -- the control
#   hero     Steve standing on the track        -- the deliverable
#   sunk     Steve 60 units below the track     -- must be pixel-identical to
#                                                  "off": the floor's own depth
#                                                  buffer hides him, which is
#                                                  what "he is in the world"
#                                                  actually means
#   fp       camera parked at Steve's eyes      -- the first person view
#
# Requires a built Ballanced (tools/poc1/build-ballanced.sh) and Xvfb on :99.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-/tmp/steve-shots}
LEVEL="3D Entities/Level/Level_01.NMO"
# Camera: eye / target. Sits above the A01_Floor_04 slab and looks along it.
CAM="-151,21,-350,-160,18.2,-366"
# Steve's feet on that slab. Scale 0.25 is not a taste decision:
# docs/scale-calibration.md pins one Minecraft block at 4.0 Ballance units
# (one ball diameter), so 1/16 block = 0.25 units and Steve is 8 units tall.
POS="-160,16.19,-366"
SUNK="-160,-43.81,-366"
SCALE=0.25

export DISPLAY=${DISPLAY:-:99}
mkdir -p "$OUT"

shoot() {
    local tag=$1 env=$2
    rm -rf "$OUT/$tag"; mkdir -p "$OUT/$tag"
    echo "== $tag"
    # env, not a bare $env: an unquoted assignment word is not expanded back
    # into an assignment, it becomes the command name.
    env OUT="$OUT/$tag" RUN_SECONDS=9 FRAMES=360 BALLANCE_POC_ISOLATE=1 \
        BALLANCE_POC_CAM="$CAM" $env \
        "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo --level "$LEVEL" \
        > "$OUT/$tag.log" 2>&1
}

shoot off  "BALLANCE_MC_STEVE=0"
shoot hero "BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_LEVEL=1 BALLANCE_MC_STEVE_YAW=180 BALLANCE_MC_STEVE_POS=$POS BALLANCE_MC_STEVE_SCALE=$SCALE"
shoot sunk "BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_YAW=180 BALLANCE_MC_STEVE_POS=$SUNK BALLANCE_MC_STEVE_SCALE=$SCALE"
shoot fp   "BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_FP=1 BALLANCE_MC_STEVE_YAW=150 BALLANCE_MC_STEVE_POS=$POS BALLANCE_MC_STEVE_SCALE=$SCALE"

python3 "$ROOT/tools/steve/steve_shots.py" "$OUT"
