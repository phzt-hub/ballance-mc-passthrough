#!/usr/bin/env bash
# Steve *is* the ball: the player ball the level hands over is I_Ball_*; Steve
# stands on the track underneath it and, with BALLANCE_MC_STEVE_CAMOFF, the
# camera rides along so the shot stays framed while the ball rolls.
#
#   ./render-ball-shots.sh [outdir]
#
#   hero3p  third person, camera 9 units behind Steve, one frame at 600, with
#           the ball mesh hidden so Steve is the thing that rolls
#   fp      first person from Steve's eyes, two frames (300 and 600) -- the two
#           frames must differ, which is what "the camera rides the ball" means
#
# Level 01's start ramp: the ball spawns near (-20, 7.4, -39) and rolls toward
# -x, so Steve faces yaw 270 and the camera sits on +x.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-/tmp/ball-shots}
LEVEL="3D Entities/Level/Level_01.NMO"
YAW=270

export DISPLAY=${DISPLAY:-:99}
mkdir -p "$OUT"

shoot() {
    local tag=$1 frames=$2 env=$3
    rm -rf "$OUT/$tag"; mkdir -p "$OUT/$tag"
    echo "== $tag (frames $frames)"
    env OUT="$OUT/$tag" RUN_SECONDS=14 FRAMES="$frames" \
        BALLANCE_POC_ISOLATE=1 BALLANCE_POC_START_LEVEL=1 \
        BALLANCE_POC_CAM="" $env \
        "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo --level "$LEVEL" \
        > "$OUT/$tag.log" 2>&1
    grep -c "is the player" "$OUT/$tag.log" | sed 's/^/   player ball frames: /'
}

shoot hero3p 600 "BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_LEVEL=1 BALLANCE_MC_STEVE_YAW=$YAW BALLANCE_MC_STEVE_CAMOFF=9,4.5,3 BALLANCE_MC_STEVE_HIDEBALL=1 BALLANCE_MC_STEVE_DEBUG=1"
shoot fp 300,600 "BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_FP=1 BALLANCE_MC_STEVE_YAW=$YAW BALLANCE_MC_STEVE_DEBUG=1"

python3 "$ROOT/tools/steve/ball_shots.py" "$OUT"
