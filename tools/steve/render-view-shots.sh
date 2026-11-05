#!/usr/bin/env bash
# Which avatar each view draws, in the real game.
#
#   ./render-view-shots.sh [outdir]
#
# Three runs, each one driving the game from the main menu into level 01 the way
# a player does (START -> LEVEL 1), because the level's own camera is the thing
# under test: the merged-level harness (BALLANCE_POC_CAM) keeps the composition's
# menu scene on screen, so a shot taken that way shows Steve standing in front of
# the menu, not in level 01.
#
#   3p-fp      VIEW=3p: the ball, and nothing of Steve. F5 -> first person: the
#              arm, and the ball gone. F5 again: back to the ball.
#   3p-noball  the same view with the ball hidden -- the ball's own pixels.
#   3p-body    the same view with the body switched on -- proof that this camera
#              *would* show Steve if the view asked for him.
#
# The three runs share the level's own follow camera, parked on a ball that is
# not moving, so the frames line up pixel for pixel once the animated pixels of
# each run are masked out (see view_check.py).
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-$ROOT/run/view-shots}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)

export DISPLAY=${DISPLAY:-:99}

WID=""
RUNNER=0

grab() {
    local dir=$1 name=$2
    eval "$(xdotool getwindowgeometry --shell "$WID")"
    ffmpeg -loglevel error -f x11grab -video_size "$WIDTH"x"$HEIGHT" \
        -i "$DISPLAY+$X,$Y" -frames:v 1 -y "$dir/$name.png" 2>/dev/null
    echo "$WIDTH $HEIGHT" > "$dir/size.txt"
}

press() {
    xdotool windowactivate --sync "$WID" 2>/dev/null
    xdotool key --clearmodifiers "$1"
}

level_loaded() {
    for _ in $(seq 1 40); do
        grep -q "level state: in a level" "$OUT/$1.log" 2>/dev/null && return 0
        sleep 1
    done
    return 1
}

# A Player from an earlier run still holding the display is the failure mode that
# bit this script first: xdotool then drives the dying window and every click
# lands nowhere. Wait for the display to be free before starting a new one.
wait_for_display() {
    for _ in $(seq 1 60); do
        pgrep -x Player >/dev/null || return 0
        sleep 1
    done
    pkill -x Player 2>/dev/null
    sleep 2
}

launch() {
    local tag=$1 envs=$2 seconds=$3
    local dir="$OUT/$tag"
    rm -rf "$dir"; mkdir -p "$dir"
    echo "== $tag"
    wait_for_display
    rm -f "$ROOT/run/game/Bin/Minecraft.ini"
    env OUT="$dir/frames" RUN_SECONDS="$seconds" FRAMES= BALLANCE_MC_HUD=1 \
        $envs "$ROOT/tools/poc1/run-poc1.sh" > "$OUT/$tag.log" 2>&1 &
    RUNNER=$!

    WID=""
    for _ in $(seq 1 80); do
        WID=$(xdotool search --onlyvisible --classname Player 2>/dev/null | tail -1)
        [ -n "$WID" ] && break
        sleep 0.25
    done
    if [ -z "$WID" ]; then
        echo "!! no window for $tag" >&2
        return 1
    fi
    xdotool windowmove "$WID" 0 0 2>/dev/null
    xdotool windowactivate --sync "$WID" 2>/dev/null
    sleep 5
    # The main menu: START opens the level list, LEVEL 1 loads the level. Retried,
    # because a click that lands while the menu is still fading in is simply lost.
    local attempt
    for attempt in 1 2 3; do
        xdotool mousemove 320 93; sleep 0.3; xdotool click 1
        sleep 2
        xdotool mousemove 320 37; sleep 0.3; xdotool click 1
        if level_loaded "$tag"; then break; fi
        echo "   level not up yet (attempt $attempt)"
    done
    if ! grep -q "level state: in a level" "$OUT/$tag.log" 2>/dev/null; then
        echo "!! level 01 never started for $tag" >&2
        return 1
    fi
    sleep 6
    # Out of the tutorial card, so the shot is the level and not a text box.
    press q
    sleep 2
}

# 1. The view tour, in one process.
launch 3p-fp "BALLANCE_MC_STEVE_VIEW=3p BALLANCE_MC_STEVE_YAW=270" 60
grab "$OUT/3p-fp" 3p-0
sleep 1
grab "$OUT/3p-fp" 3p-1
press F5
sleep 1.5
grab "$OUT/3p-fp" fp-0
sleep 1
grab "$OUT/3p-fp" fp-1
press F5
sleep 1.5
grab "$OUT/3p-fp" back-3p
wait "$RUNNER" 2>/dev/null

# 2. The ball's own pixels.
launch 3p-noball "BALLANCE_MC_STEVE_VIEW=3p BALLANCE_MC_STEVE_YAW=270 BALLANCE_MC_STEVE_HIDEBALL=1" 30
grab "$OUT/3p-noball" noball-0
sleep 1
grab "$OUT/3p-noball" noball-1
wait "$RUNNER" 2>/dev/null

# 3. The positive control: the same camera with Steve switched on.
launch 3p-body "BALLANCE_MC_STEVE_VIEW=3p BALLANCE_MC_STEVE_YAW=270 BALLANCE_MC_STEVE=1" 30
grab "$OUT/3p-body" body-0
sleep 1
grab "$OUT/3p-body" body-1
wait "$RUNNER" 2>/dev/null

python3 "$ROOT/tools/steve/view_check.py" "$OUT"
