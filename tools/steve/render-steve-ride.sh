#!/usr/bin/env bash
# Close-up: Steve riding the player ball, camera 20 units behind him.
#
#   ./render-steve-ride.sh [outdir]
#
# This is the follow-up the 2026-11-04 checkpoint asked for: the standing shot
# had the camera 48 units away, so an 8-unit Steve was only 39x56 pixels and the
# verifier's 3000-pixel floor was out of reach. Here the camera is attached to
# Steve himself (BALLANCE_MC_STEVE_CAMOFF), which keeps him framed at ~20 units
# however far the ball rolls.
#
# Three frames, and the control is "same camera, model hidden"
# (BALLANCE_MC_STEVE_HIDE=1) rather than "Steve off": with the camera attached to
# Steve, turning him off also turns the camera off, and then the diff would be
# measuring the camera move instead of the model.
#
#   hide   camera attached, Steve invisible  -- the control
#   hero   camera attached, Steve drawn      -- the deliverable
#   fp     camera at Steve's eyes            -- first person on the ball
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-$ROOT/run/steve-ride}
LEVEL="3D Entities/Level/Level_01.NMO"
# feet + (0, 5, 20) -- the +z side is the side Steve faces (yaw 0), so the
# camera looks at his front and the skin's face texels are in shot.
CAMOFF=${CAMOFF:-0,5,20}
YAW=${YAW:-0}
# The ball is physics driven, so two runs of the same build drift apart within a
# second. Dumping early keeps the control and the hero frame on the same part of
# the track; the numbers below are where both runs still agree to 0.05 units.
FRAMES=${FRAMES:-90}
RUN_SECONDS=${RUN_SECONDS:-4}

export DISPLAY=${DISPLAY:-:99}
mkdir -p "$OUT"

shoot() {
    local tag=$1 env=$2
    rm -rf "$OUT/$tag"; mkdir -p "$OUT/$tag"
    echo "== $tag"
    env OUT="$OUT/$tag" RUN_SECONDS="$RUN_SECONDS" FRAMES="$FRAMES" BALLANCE_POC_ISOLATE=1 \
        $env \
        "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo --level "$LEVEL" \
        > "$OUT/$tag.log" 2>&1
}

shoot hide "BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_LEVEL=1 BALLANCE_MC_STEVE_HIDE=1 BALLANCE_MC_STEVE_CAMOFF=$CAMOFF BALLANCE_MC_STEVE_YAW=$YAW BALLANCE_MC_STEVE_DEBUG=1"
shoot hero "BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_CAMOFF=$CAMOFF BALLANCE_MC_STEVE_YAW=$YAW BALLANCE_MC_STEVE_DEBUG=1"
shoot fp   "BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_FP=1 BALLANCE_MC_STEVE_YAW=$YAW BALLANCE_MC_STEVE_DEBUG=1"

python3 "$ROOT/tools/steve/steve_ride_check.py" "$OUT"
