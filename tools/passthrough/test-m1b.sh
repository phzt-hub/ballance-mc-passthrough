#!/usr/bin/env bash
# M1b acceptance: the E key and the pointer reach Minecraft through the camera
# channel, the inventory freezes the level, and the aim lift moves the crosshair
# off the ball.
#
#   ./test-m1b.sh [tag] [seconds-in-level]
#
# Prerequisite: the Minecraft client is already running with the passthrough mod
# (cd mc-mod && DISPLAY=:99 ./gradlew25 runClient). The Ballance window is parked
# at 0,0 and every grab is that exact 640x480 rect.
set -uo pipefail

TAG=${1:-m1b}
SETTLE=${2:-12}
ROOT=/home/phzt/src/ballance-mc-passthrough
OUT=/tmp/m1b/$TAG
export DISPLAY=${DISPLAY:-:99}

cd "$ROOT"
rm -rf "$OUT"; mkdir -p "$OUT"

if [ ! -e /dev/shm/ballance_mc_frame ]; then
    echo "== no producer: /dev/shm/ballance_mc_frame is missing (is Minecraft running?)"
    exit 1
fi

geom() {
    xwininfo -id "$1" 2>/dev/null | awk '
        /Absolute upper-left X/ {print "X=" $4}
        /Absolute upper-left Y/ {print "Y=" $4}
        /Width:/ {print "W=" $2}
        /Height:/ {print "H=" $2}'
}

place() {
    local wid=$1 px=$2 py=$3 tries=0
    while [ $tries -lt 8 ]; do
        xdotool windowmove "$wid" "$px" "$py" 2>/dev/null
        sleep 0.4
        eval "$(geom "$wid")"
        [ "${X:-x}" = "$px" ] && [ "${Y:-x}" = "$py" ] && return 0
        tries=$((tries + 1))
    done
    echo "   (window $wid did not move to $px,$py; at ${X},${Y})" >&2
    return 1
}

grab() {
    ffmpeg -loglevel error -f x11grab -video_size "${W}x${H}" -i "${DISPLAY}+${X},${Y}" \
        -frames:v 1 -y "$OUT/$1.png" 2>/dev/null
    echo "   shot $1"
}

# Park the Minecraft window out of the way; the Ballance window is what we grab.
MCWID=$(xwininfo -root -tree 2>/dev/null | grep -i 'Minecraft' | grep -oE '0x[0-9a-f]+' | head -1)
[ -n "$MCWID" ] && place "$MCWID" 900 560

RUN_SECONDS=${RUN_SECONDS:-110} \
    BALLANCE_MC_PASSTHROUGH=1 BALLANCE_MC_CAMERA=1 \
    BALLANCE_MC_STEVE=0 BALLANCE_MC_STEVE_FP=0 \
    ./tools/poc1/run-poc1.sh --cmo base.cmo --verbose > "$OUT/game.log" 2>&1 &
GAME=$!

WID=""
for _ in $(seq 1 60); do
    WID=$(xwininfo -root -tree 2>/dev/null | grep -E '640x480' | grep -v Minecraft \
        | grep -oE '0x[0-9a-f]+' | tr -d ' ' | head -1)
    [ -n "$WID" ] && break
    sleep 0.5
done
if [ -z "$WID" ]; then
    echo "== no Ballance window"
    kill "$GAME" 2>/dev/null
    exit 1
fi
sleep 2.5
place "$WID" 0 0
eval "$(geom "$WID")"
echo "== ballance window $WID at ${X},${Y} ${W}x${H}"

# The level has to be reached through the menu, and the aim lift only exists in
# a level (the camera channel publishes a ball there).
./tools/passthrough/enter-level-8.sh 4 > "$OUT/menu.log" 2>&1
echo "== level 8 entered, settling ${SETTLE}s"
sleep "$SETTLE"

# Pointer inside the window first: key events go to whatever the pointer is over
# on an X server without a window manager.
xdotool mousemove $((X + W / 2)) $((Y + H / 2))
sleep 0.3
grab 00-before

key() {
    xdotool keydown "$1"; sleep 0.12; xdotool keyup "$1"; sleep "${2:-1.5}"
}

echo "== E"
key e 2.5
grab 01-inventory

# Click a slot, then a hotbar slot: the item has to end up in the hotbar, which
# is visible in the last shot after the inventory is closed.
click_at() {
    xdotool mousemove $((X + $1)) $((Y + $2)); sleep 0.4
    xdotool mousedown 1; sleep 0.25; xdotool mouseup 1; sleep 0.9
}

# The defaults are the centre of real widgets, worked out from the creative
# screen's own layout (image 195x136 centred in a 427x240 GUI, tabs of 26x32 at
# y=-32 step 27, item grid at 9,18 step 18, hotbar row at y=112) and mapped onto
# the 640x480 Ballance window (x * 640/427, y * 480/240):
#   tab 2 (not the default one, so a click that works is visible)
#                     GUI (183, 36)  -> window (274,  72)
#   item grid row 1 col 4
#                     GUI (205, 96)  -> window (307, 192)
#   hotbar slot 0/1   GUI (133/151,172) -> window (199/227, 344)
echo "== click tab 2 ${CLICK_TAB:-274 72}"
click_at ${CLICK_TAB:-274 72}
grab 01b-tab

echo "== click slot ${CLICK_SLOT:-307 192}"
click_at ${CLICK_SLOT:-307 192}
grab 02-picked
echo "== click hotbar slot 0 ${CLICK_HOTBAR:-199 344}"
click_at ${CLICK_HOTBAR:-199 344}
grab 03-placed

# The wheel and the right button go through the same channel; the shots only
# prove they were delivered without breaking anything, the log has the carried
# stack for each of them.
echo "== wheel down 3 notches"
xdotool mousemove $((X + 307)) $((Y + 192)); sleep 0.3
xdotool click --repeat 3 --delay 150 5; sleep 1.0
grab 03b-scroll

echo "== right-click pick ${CLICK_SLOT:-307 192}"
xdotool mousemove $((X + 307)) $((Y + 192)); sleep 0.3
xdotool mousedown 3; sleep 0.25; xdotool mouseup 3; sleep 0.9
grab 03c-rightpick
echo "== right-click hotbar slot 1 (227 344)"
xdotool mousemove $((X + 227)) $((Y + 344)); sleep 0.3
xdotool mousedown 3; sleep 0.25; xdotool mouseup 3; sleep 0.9
grab 03d-rightplace

echo "== E to close"
key e 2.0
grab 04-closed

kill "$GAME" 2>/dev/null
wait "$GAME" 2>/dev/null
echo "== click / carried log"
grep -a "mc-camera" "$OUT/game.log" | grep -aE "left |right |carried" | tail -12
echo "== camera log"
grep -a "mc-camera" "$OUT/game.log" | grep -aE "inventory|aim|lift" | tail -8
grep -a "mc-camera" "$OUT/game.log" | tail -3
echo "== shots: $(ls "$OUT"/*.png 2>/dev/null | wc -l)"
