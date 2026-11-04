#!/usr/bin/env bash
# M0b acceptance: the REAL Minecraft client publishes the frames.
#
#   ./test-m0b.sh <tag> [seconds] [on|off]
#
# Prerequisite: Minecraft is already running with the xpconvert client mod
# (cd ~/mcmod && DISPLAY=:99 ./gradlew25 runClient) so that
# /dev/shm/ballance_mc_frame exists and advances. "off" runs the same scene with
# the consumer disabled, which is the control shot; "on" (default) draws the live
# Minecraft frame inside the Ballance window.
#
# Under Xvfb there is no window manager, so windows overlap each other in a screen
# grab. Minecraft is parked in the bottom-right quadrant and the Ballance window
# (which SDL centres at 480,260 a moment after mapping) is pushed to the top-left
# corner once it has settled, with the position verified before any grab. Frames
# are then sampled several times: a steady overlay looks the same on every sample,
# which is exactly what the flicker bug of 2026-11-04 did not.
set -uo pipefail

TAG=${1:-m0b}
SECS=${2:-25}
MODE=${3:-on}
ROOT=/home/phzt/src/ballance-mc-passthrough
OUT=/tmp/m0b/$TAG
export DISPLAY=${DISPLAY:-:99}
SHM=/dev/shm/ballance_mc_frame

cd "$ROOT"
rm -rf "$OUT"; mkdir -p "$OUT"

if [ ! -e "$SHM" ]; then
    echo "== no producer: $SHM is missing (is the Minecraft client running?)"
    exit 1
fi

# Absolute upper-left corner + size of a window, as shell assignments.
geom() {
    xwininfo -id "$1" 2>/dev/null | awk '
        /Absolute upper-left X/ {print "X=" $4}
        /Absolute upper-left Y/ {print "Y=" $4}
        /Width:/ {print "W=" $2}
        /Height:/ {print "H=" $2}'
}

# Move a window and only trust the result once xwininfo agrees.
place() {
    local wid=$1 px=$2 py=$3 tries=0
    while [ $tries -lt 6 ]; do
        xdotool windowmove "$wid" "$px" "$py" 2>/dev/null
        sleep 0.4
        eval "$(geom "$wid")"
        [ "${X:-x}" = "$px" ] && [ "${Y:-x}" = "$py" ] && return 0
        tries=$((tries + 1))
    done
    echo "   (window $wid did not move to $px,$py; at ${X},${Y})" >&2
    return 1
}

MCWID=$(xwininfo -root -tree 2>/dev/null | grep -i 'Minecraft' | grep -oE '0x[0-9a-f]+' | head -1)
if [ -n "$MCWID" ]; then
    place "$MCWID" 746 520
fi

python3 tools/passthrough/inspect-mailbox.py --watch 3 --png "$OUT/mc-frame.png" \
    > "$OUT/mailbox.json" 2>&1

PT=0
[ "$MODE" = "on" ] && PT=1

OUT="$OUT" RUN_SECONDS="$SECS" FRAMES="" \
    BALLANCE_MC_PASSTHROUGH="$PT" BALLANCE_POC_FRAMELOG=120 \
    ./tools/poc1/run-poc1.sh --cmo base.cmo --verbose > "$OUT/game.log" 2>&1 &
GAME=$!

WID=""
for _ in $(seq 1 40); do
    WID=$(xwininfo -root -tree 2>/dev/null | grep -E '640x480' | grep -v Minecraft \
        | grep -oE '0x[0-9a-f]+' | tr -d ' ' | head -1)
    [ -n "$WID" ] && break
    sleep 0.5
done

if [ -z "$WID" ]; then
    echo "== no Ballance window"
    wait "$GAME" 2>/dev/null
    exit 1
fi

# SDL centres the window itself shortly after mapping; let it settle, then move.
sleep 2.5
place "$WID" 0 0
eval "$(geom "$WID")"
echo "== ballance window $WID at ${X},${Y} ${W}x${H} (mode $MODE, passthrough $PT)"

# Six samples over ~4 s: a steady overlay looks the same on all of them.
for i in 1 2 3 4 5 6; do
    ffmpeg -loglevel error -f x11grab -video_size "${W}x${H}" -i "${DISPLAY}+${X},${Y}" \
        -frames:v 1 -y "$OUT/shot$i.png" 2>/dev/null
    sleep 0.7
done

wait "$GAME" 2>/dev/null
echo "== shots: $(ls "$OUT"/shot*.png 2>/dev/null | wc -l)"
grep -a "mc-passthrough" "$OUT/game.log" | tail -4
grep -a "^\[frame\]" "$OUT/game.log" | tail -1
