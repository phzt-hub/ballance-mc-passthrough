#!/usr/bin/env bash
# Does the pointer come back when the pause menu opens -- and does the mouse stop
# turning the head while it is up?
#
#   ./menu_shots.sh [outdir]
#
# One first-person run on Level_01 with Steve parked at a fixed position, so the
# only thing that can move is the head. The same 200px of pointer motion is
# driven three times, with a real Escape key press in between:
#
#   burst 1  playing     -> yaw must move by -28.6 deg
#   Escape   menu up     -> pointer released, burst 2 must move yaw by 0.0 deg
#   Up key   playing     -> pointer recaptured, burst 3 must move -28.6 deg
#
# Zero alone would also pass if the pointer had simply stopped working (capture
# lost, window unfocused, xdotool missing), so two controls pin it down:
#
#   * burst 3, which says the mechanism recovered rather than died;
#   * pointer.txt, the OS cursor position sampled around every burst. While the
#     pointer is captured SDL keeps it inside the window, so its travel is small
#     and the head turns instead; while it is released nothing confines it, so the
#     same 200px walks the cursor out of the window and the head does not move.
#     That separates "the gate ignored the mouse" from "the mouse never arrived".
#
# Both keys are sent as a single xdotool tap (press and release in the same X
# batch). That is the hard case -- SDL can deliver both events inside one frame,
# where IsKeyDown() never sees the press -- and the engine answers it from the
# frame's key buffer (KeyTappedThisFrame).
#
# Why Escape and not "wait for the game to say a menu is up": in this port
# SdlInputManager::ShowCursor() pins the cursor flag to TRUE (BALLANCE_SPRITE_CURSOR
# controls that) because the game's own sprite cursor is not ported, so no engine
# flag reports a menu. Escape is the game's own menu key, and the release follows
# the key the player actually presses.
set -o pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$1"
[ -n "$OUT" ] || OUT="$ROOT/run/menu-shots"
mkdir -p "$OUT"
LEVEL="3D Entities/Level/Level_01.NMO"
POS="-160,16.19,-366"
LOG="$OUT/menu.log"

[ -n "$DISPLAY" ] || DISPLAY=:99
export DISPLAY
rm -rf "$OUT/frames"
mkdir -p "$OUT/frames"
: > "$OUT/pointer.txt"

env BALLANCE_POC_ISOLATE=1 BALLANCE_MC_STEVE=1 BALLANCE_MC_STEVE_LEVEL=1 BALLANCE_MC_STEVE_FP=1 BALLANCE_MC_STEVE_YAW=0 \
    BALLANCE_MC_STEVE_SCALE=0.25 BALLANCE_MC_STEVE_POS="$POS" BALLANCE_MC_STEVE_DEBUG=1 \
    OUT="$OUT/frames" RUN_SECONDS=40 FRAMES= \
    "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo --level "$LEVEL" > "$LOG" 2>&1 &
runner=$!

for _ in $(seq 1 60); do grep -q "mc-steve. built" "$LOG" 2>/dev/null && break; sleep 0.5; done
sleep 4
W=$(xdotool search --onlyvisible --classname Player 2>/dev/null | tail -1)
echo "== window: $W"
if [ -n "$W" ]; then
    eval "$(xdotool getwindowgeometry --shell "$W")"
    echo "== geometry: $WIDTH x $HEIGHT at $X,$Y"
    xdotool windowactivate --sync "$W" 2>/dev/null
    xdotool mousemove --window "$W" $((WIDTH / 2)) $((HEIGHT / 2))
    sleep 1
fi

sample_pointer() {
    eval "$(xdotool getmouselocation --shell)"
    echo "$X $Y"
}

burst() {
    local tag=$1 before after
    before=$(sample_pointer)
    echo "== $tag $(date +%H:%M:%S)"
    for _ in $(seq 1 10); do xdotool mousemove_relative -- 20 0; sleep 0.05; done
    sleep 2
    after=$(sample_pointer)
    echo "$tag $before $after" >> "$OUT/pointer.txt"
    echo "== $tag pointer $before -> $after"
}

burst burst1

echo "== escape (open menu) $(date +%H:%M:%S)"
xdotool key --clearmodifiers Escape
sleep 3
burst burst2

echo "== up (back in the game) $(date +%H:%M:%S)"
xdotool key --clearmodifiers Up
sleep 3
burst burst3

wait "$runner" 2>/dev/null
python3 "$ROOT/tools/steve/menu_check.py" "$LOG" "$OUT/pointer.txt"
