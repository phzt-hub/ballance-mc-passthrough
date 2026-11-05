#!/usr/bin/env bash
# Enter level 1 through Ballance's own menu, the way a player does.
#
# Why not --level: the level's gameplay camera (InGameCam) is attached by the
# menu's start flow. Loading the level behind the menu (BALLANCE_POC_ISOLATE)
# leaves Cam_MenuLevel attached, so the frame is an orbit of the level rather
# than a view from the ball -- measured on 2026-11-05, and the reason this
# script exists: the M1 camera channel publishes whatever renders, so the
# harness has to render the same thing the product renders.
#
#   ./enter-level.sh [delay-before-start] [delay-before-level]
#
# Requires the game to be running on $DISPLAY with its window already up.
set -uo pipefail

export DISPLAY=${DISPLAY:-:99}
BEFORE=${1:-4}
BETWEEN=${2:-6}

window=$(xwininfo -root -tree 2>/dev/null | grep -E '^ *0x[0-9a-f]+ "Ballance"' | awk '{print $1}' | tail -1)
if [ -z "${window:-}" ]; then
    echo "enter-level: no Ballance window on $DISPLAY" >&2
    exit 1
fi

# The Minecraft client parks itself in the same corner (SDL centres any window
# of this size), so without a window manager the click would land on whichever
# window is on top. Raise this one; do NOT move it -- moving a window that is
# mid-frame confuses its mouse handling here (measured: after a windowmove the
# Start button stopped reacting, before it always worked).
xdotool windowraise "$window" 2>/dev/null
sleep 1

read -r wx wy ww wh <<<"$(xwininfo -id "$window" | awk '
    /Absolute upper-left X/ { x = $4 }
    /Absolute upper-left Y/ { y = $4 }
    /^ *Width:/ { w = $2 }
    /^ *Height:/ { h = $2 }
    END { print x, y, w, h }')"
echo "enter-level: window $window at ${wx},${wy} ${ww}x${wh}"

# Positions are fractions of the 640x480 layout, so they survive a resize.
click() { xdotool mousemove "$((wx + ww * $1 / 1000))" "$((wy + wh * $2 / 1000))" click 1; }

sleep "$BEFORE"
# M_Main_But_1 (Start): centre (320, 94.5) of 640x480.
click 500 197
sleep "$BETWEEN"
# M_Start_But_01 (level 1): centre (320, 37.7) of 640x480.
click 500 79
echo "enter-level: clicked Start and level 1"
