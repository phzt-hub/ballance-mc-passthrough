#!/usr/bin/env bash
# Enter any level through Ballance's own menu, with the level list unlocked.
#
#   ./enter-level-n.sh <1..13> [delay-after-window]
#
# enter-level.sh drives level 1 only (the first button of the level list), and a
# profile that has never been played has nothing else selectable. The original
# game's credits-screen cheat -- typing `gravity` on the credits page -- unlocks
# all thirteen, which is what enter-level-8.sh does for its single level. This is
# that flow with the level number as an argument, so the level import can be
# acceptance-tested on the levels that matter (3, 5, 7, 9, 11: the five starts
# that share a floor and used to leave the world empty).
#
# Geometry: the level buttons are one column in the 640x480 layout, level 1 at
# y = 37.7 and level 8 at y = 239 (both measured with the 2D dump), i.e. 28.76 px
# apart. Requires the game to be running on $DISPLAY with its window already up.
set -uo pipefail

export DISPLAY=${DISPLAY:-:99}
LEVEL=${1:-1}
BEFORE=${2:-5}

if ! [[ "$LEVEL" =~ ^[0-9]+$ ]] || [ "$LEVEL" -lt 1 ] || [ "$LEVEL" -gt 13 ]; then
    echo "enter-level-n: level must be 1..13, got '$LEVEL'" >&2
    exit 2
fi

window=$(xwininfo -root -tree 2>/dev/null | grep -E '^ *0x[0-9a-f]+ "Ballance"' | awk '{print $1}' | tail -1)
if [ -z "${window:-}" ]; then
    echo "enter-level-n: no Ballance window on $DISPLAY" >&2
    exit 1
fi

read -r wx wy <<<"$(xwininfo -id "$window" | awk '
    /Absolute upper-left X/ { x = $4 }
    /Absolute upper-left Y/ { y = $4 }
    END { print x + 0, y + 0 }')"
echo "enter-level-n: window $window at ${wx},${wy}; going to level $LEVEL"

# Move the pointer to a window-relative point in twelve steps, then click.
# A jump leaves the game's own cursor on the previous position and the click
# lands on whatever was under THAT (learned in enter-level-8.sh).
at() {
    local tx=$(( wx + $1 )) ty=$(( wy + $2 ))
    local fx=$(( wx + ${3:-320} )) fy=$(( wy + ${4:-240} ))
    local i
    for i in $(seq 1 12); do
        xdotool mousemove $(( fx + (tx - fx) * i / 12 )) $(( fy + (ty - fy) * i / 12 ))
        sleep 0.05
    done
    sleep 0.35
    xdotool mousedown 1
    sleep 0.3
    xdotool mouseup 1
    sleep 0.6
}

sleep "$BEFORE"
# M_Main_But_4 (CREDITS): centre (320, 310) of 640x480.
at 320 310 320 240
sleep 3
xdotool type --delay 160 'gravity'
echo "enter-level-n: typed gravity on the credits page (unlocks every level)"
sleep 2
# M_Credits_But_Back: centre (320, 425).
at 320 425 320 310
sleep 2
# M_Main_But_1 (START): centre (320, 95).
at 320 95 320 425
sleep 4

Y=$(awk -v n="$LEVEL" 'BEGIN { printf "%d", 37.7 + (n - 1) * 28.757 + 0.5 }')
at 320 "$Y" 320 95
echo "enter-level-n: clicked LEVEL $LEVEL (y=$Y of 640x480)"
