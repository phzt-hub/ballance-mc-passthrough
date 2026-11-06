#!/usr/bin/env bash
# Enter level 8 through Ballance's own menu.
#
#   ./enter-level-8.sh [delay-after-window]
#
# Requires the game to be running on $DISPLAY with its window already up.
#
# Two things had to be learned the hard way:
#
#   * A plain `xdotool ... click` is not enough. The pointer has to travel to
#     the button in small steps first; a jump leaves the game's own cursor on
#     the previous position, so the click lands on whatever was under THAT.
#     Symptom: clicking CREDITS does nothing and OPTIONS stays highlighted.
#   * A fresh profile has only level 1 selectable, and the menu decides that
#     when it initialises: setting DB_Levelfreischaltung afterwards changes
#     nothing. The original game's credits-screen cheat is the way in -- type
#     `gravity` on the credits page, and every level unlocks.
set -uo pipefail

export DISPLAY=${DISPLAY:-:99}
BEFORE=${1:-5}

window=$(xwininfo -root -tree 2>/dev/null | grep -E '^ *0x[0-9a-f]+ "Ballance"' | awk '{print $1}' | tail -1)
if [ -z "${window:-}" ]; then
    echo "enter-level-8: no Ballance window on $DISPLAY" >&2
    exit 1
fi

read -r wx wy <<<"$(xwininfo -id "$window" | awk '
    /Absolute upper-left X/ { x = $4 }
    /Absolute upper-left Y/ { y = $4 }
    END { print x + 0, y + 0 }')"
echo "enter-level-8: window $window at ${wx},${wy}"

# Move the pointer to a window-relative point in twelve steps, then click.
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
echo "enter-level-8: typed gravity on the credits page"
sleep 2
# M_Credits_But_Back: centre (320, 425).
at 320 425 320 310
sleep 2
# M_Main_But_1 (START): centre (320, 95).
at 320 95 320 425
sleep 4
# M_Start_But_08 (level 8): centre (320, 239), see the 2D dump.
at 320 239 320 95
echo "enter-level-8: clicked LEVEL 8"
