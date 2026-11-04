#!/usr/bin/env bash
# M0 acceptance run: does a foreign frame published through the shared memory
# mailbox reach the Ballance window, and what does it cost?
#
#   ./test-m0.sh <tag> [seconds] [off|static|anim]
#
# off     - passthrough disabled, the control shots
# static  - producer publishes one unchanging pattern (geometry/colour check)
# anim    - producer publishes a moving pattern (liveness check)
set -uo pipefail

TAG=${1:-m0}
SECS=${2:-25}
MODE=${3:-static}
ROOT=/home/phzt/src/ballance-mc-passthrough
OUT=/tmp/m0/$TAG
export DISPLAY=${DISPLAY:-:99}
SHM=/dev/shm/ballance_mc_frame

cd "$ROOT"
rm -rf "$OUT"; mkdir -p "$OUT"

PUB=
if [ "$MODE" != "off" ]; then
    rm -f "$SHM"
    extra=""
    [ "$MODE" = "static" ] && extra="--static"
    ./tools/passthrough/shmfeed --width 320 --height 240 --fps 60 $extra > "$OUT/producer.log" 2>&1 &
    PUB=$!
    sleep 0.5
fi

PT=0
[ "$MODE" != "off" ] && PT=1

OUT="$OUT" RUN_SECONDS="$SECS" FRAMES="" \
    BALLANCE_MC_PASSTHROUGH="$PT" BALLANCE_POC_FRAMELOG=120 \
    ./tools/poc1/run-poc1.sh --cmo base.cmo --verbose > "$OUT/game.log" 2>&1 &
GAME=$!

sleep 6
WID=$(xwininfo -root -tree 2>/dev/null | grep -oE "0x[0-9a-f]+ .*640x480" | grep -oE "^0x[0-9a-f]+" | head -1)
if [ -z "$WID" ]; then echo "== no window"; else
    eval "$(xwininfo -id "$WID" | awk '
        /Absolute upper-left X/ {print "X=" $4}
        /Absolute upper-left Y/ {print "Y=" $4}
        /Width:/ {print "W=" $2}
        /Height:/ {print "H=" $2}')"
    echo "== window $WID at ${X},${Y} ${W}x${H}"
    for i in 1 2 3 4; do
        ffmpeg -loglevel error -f x11grab -video_size "${W}x${H}" -i "${DISPLAY}+"${X},"${Y}" \
            -frames:v 1 -y "$OUT/shot$i.png" 2>/dev/null
        sleep 1
    done
fi

wait "$GAME" 2>/dev/null
[ -n "$PUB" ] && kill "$PUB" 2>/dev/null && wait "$PUB" 2>/dev/null
[ -n "$PUB" ] && rm -f "$SHM"
echo "== shots: $(ls "$OUT"/shot*.png 2>/dev/null | wc -l)"
grep -a "mc-passthrough" "$OUT/game.log" | head -8
grep -a "^\[frame\]" "$OUT/game.log" | tail -1
