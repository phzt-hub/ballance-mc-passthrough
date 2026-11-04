#!/usr/bin/env bash
# Proof that the mouse reaches Steve's look path, and that the head follows it.
#
#   ./probe-look.sh [outdir]
#
# Injects pointer motion with xdotool, so this measures the same path a real
# mouse takes: X11 -> SDL_EVENT_MOUSE_MOTION -> m_RelMouseX/Y -> lX/lY ->
# CMinecraftSteve::UpdateLook. Nothing is faked inside the engine.
#
# Expect, with sens 0.0025 rad/px:
#   10 x 20px right -> yaw   -28.6 deg, pitch unchanged
#   6 x 10px up     -> pitch  +8.6 deg, yaw unchanged
#
# Trap worth remembering: the game window is 640x480 at +480+260, so
# "xdotool mousemove --window W 800 500" aims *outside* the window and SDL then
# reports no motion at all -- which reads exactly like "the mouse is not read".
# Always aim at the window's own centre, measured, never guessed.
set -o pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$1"; [ -n "$OUT" ] || OUT="$ROOT/run/look-probe"
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
LEVEL="3D Entities/Level/Level_01.NMO"
[ -n "$POS" ] || POS=-160,16.19,-366
[ -n "$RUN_SECONDS" ] || RUN_SECONDS=24
[ -n "$DISPLAY" ] || DISPLAY=:99; export DISPLAY

LOG="$OUT/look.log"
env BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_FP=1 BALLANCE_MC_STEVE_YAW=0 BALLANCE_MC_STEVE_SCALE=0.25 BALLANCE_MC_STEVE_POS=$POS BALLANCE_MC_STEVE_DEBUG=1 BALLANCE_INPUT_DEBUG=1 OUT="$OUT/frames" RUN_SECONDS=$RUN_SECONDS FRAMES= "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo --level "$LEVEL" > "$LOG" 2>&1 &
RUNNER=$!

for _ in $(seq 1 60); do
    grep -q "mc-steve. built" "$LOG" 2>/dev/null && break
    sleep 0.5
done
sleep 3

W=$(xdotool search --onlyvisible --classname Player 2>/dev/null | tail -1)
echo "== window: $W"
if [ -n "$W" ]; then
    eval "$(xdotool getwindowgeometry --shell "$W")"   # X Y WIDTH HEIGHT SCREEN
    echo "== geometry: ${WIDTH}x${HEIGHT}+${X}+${Y}"
    xdotool windowactivate --sync "$W" 2>/dev/null
    xdotool mousemove --window "$W" $((WIDTH / 2)) $((HEIGHT / 2))
    sleep 1
    # Two independent axes: a log line that moves only one of them cannot pass
    # by accident. Kept inside the window: 200px right and 60px up from centre
    # both stay within 640x480.
    for _ in $(seq 1 10); do xdotool mousemove_relative -- 20 0; sleep 0.05; done
    sleep 1
    for _ in $(seq 1 6); do xdotool mousemove_relative -- 0 -10; sleep 0.05; done
    sleep 2
fi
kill "$RUNNER" 2>/dev/null
wait "$RUNNER" 2>/dev/null
echo "== look lines (last 4)"
grep -a "mc-steve. look:" "$LOG" | tail -4
echo "== pose lines (last 3)"
grep -a "mc-steve. at (" "$LOG" | tail -3
echo "== motion events seen by the input manager: $(grep -ac 'input-debug. SDL_EVENT_MOUSE_MOTION' "$LOG")"
python3 "$ROOT/tools/steve/look_check.py" "$LOG"
