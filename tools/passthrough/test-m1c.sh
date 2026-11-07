#!/usr/bin/env bash
# M1c acceptance: the world's mouse buttons reach Minecraft, and a block can be placed
# with nothing under the aim (a void world has nothing to place against).
#
#   ./test-m1c.sh [tag] [seconds-in-level]
#
# Prerequisite: the Minecraft client is already running with the passthrough mod
# (cd mc-mod && DISPLAY=:99 ./gradlew25 runClient). The Ballance window is parked at 0,0
# and every grab is that exact 640x480 rect.
set -uo pipefail

TAG=${1:-m1c}
SETTLE=${2:-12}
ROOT=/home/phzt/src/ballance-mc-passthrough
OUT=/tmp/m1c/$TAG
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

# The Minecraft frame straight out of the mailbox: the blocks without anything drawn over
# them, which is where their size can be measured against the Ballance scene.
mcframe() {
    python3 tools/passthrough/inspect-mailbox.py --png "$OUT/$1.png" >/dev/null 2>&1
    echo "   mc frame $1"
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

# The level has to be reached through the menu: the camera channel only publishes a ball
# in a level, and the player is only on the ball once that happens.
./tools/passthrough/enter-level-8.sh 4 > "$OUT/menu.log" 2>&1
echo "== level 8 entered, settling ${SETTLE}s"
sleep "$SETTLE"

xdotool mousemove $((X + W / 2)) $((Y + H / 2))
sleep 0.3

key() {
    xdotool keydown "$1"; sleep 0.12; xdotool keyup "$1"; sleep "${2:-1.5}"
}

click_at() {
    xdotool mousemove $((X + $1)) $((Y + $2)); sleep 0.4
    xdotool mousedown 1; sleep 0.25; xdotool mouseup 1; sleep 0.9
}

world_click() {
    local button=$1
    xdotool mousemove $((X + W / 2)) $((Y + H / 2)); sleep 0.3
    xdotool mousedown "$button"; sleep 0.3; xdotool mouseup "$button"; sleep 1.6
}

# 1) Put a block in hotbar slot 0 through the creative inventory.
echo "== E"
key e 2.5
grab 00-inventory
# Slots are in window pixels, and the creative screen draws at GUI scale 2 in a 640x480
# window: the item grid starts at (150,130) with 36 px cells, the hotbar row at y 382..420.
# The carried stack goes into hotbar slot 0 with the "1" key -- vanilla swaps a carried
# stack into that slot in any container screen. Clicking the screen's hotbar row is not
# reliable here: at GUI scale 2 its cells do not line up with the HUD hotbar's, and a click
# a few pixels off either misses the slot or lands on a tab.
echo "== click slot (167 150), then key 1 to put it in hotbar slot 0"
click_at 167 150
grab 01-picked
key 1 1.5
grab 02-hotbar
echo "== E to close"
key e 2.0
# The creative screen puts the picked stack in hotbar slot 1 (a click on the screen's own
# hotbar row lands a cell off), and the mod selects slot 0 at world entry, so roll one notch
# down to hold it. Verified by the "held=" field of the next world click.
xdotool click 5; sleep 1.2
grab 03-world

# 2) The world click. Nothing is under the aim in a void world, so this is the branch
#    that spawns the block at the end of the reach.
echo "== world right-click (place)"
world_click 3
grab 04-placed
mcframe 04-mc

# 3) The same button again: the block that was just placed is now under the aim, so this
#    one has to take the vanilla path instead (the log says aim=<pos>/<face>, not MISS).
echo "== world right-click again (vanilla place onto the new block)"
world_click 3
grab 05-stacked
mcframe 05-mc

# 4) Left click: startAttack -> startDestroyBlock, creative one-shots the block.
echo "== world left-click (break)"
world_click 1
grab 06-broken

kill "$GAME" 2>/dev/null
wait "$GAME" 2>/dev/null
# The input log is Minecraft's (this half of the story); Ballance's camera log is game.log.
MC_LOG=${MC_LOG:-/tmp/mc-run.log}
echo "== world input log ($MC_LOG)"
grep -a "ballance-passthrough-input" "$MC_LOG" | grep -aE "world (left|right)" | tail -12
echo "== placed: $(grep -ac 'placed ' "$MC_LOG" 2>/dev/null || echo 0) line(s)"
echo "== shots: $(ls "$OUT"/*.png 2>/dev/null | wc -l)"
