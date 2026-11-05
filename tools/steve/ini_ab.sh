#!/usr/bin/env bash
# Does Minecraft.ini alone switch Steve on/off -- with no BALLANCE_MC_STEVE*
# environment variable in play?
#
#   ./ini_ab.sh [outdir]
#
# The accepted third-person A/B walked through the menu into Level_01 and put
# Steve next to the ball.  Same walk here, but the only difference between the
# two runs is the "Steve=" line in run/game/Bin/Minecraft.ini: the A/B is the
# ini, nothing else.
set -o pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$1"; [ -n "$OUT" ] || OUT="$ROOT/run/ini-ab"
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
GAME="$ROOT/run/game"
INI="$GAME/Bin/Minecraft.ini"
export DISPLAY=${DISPLAY:-:99}

set_steve() {
    [ -f "$INI" ] || { echo "no $INI -- launch the game once so it writes the default file" >&2; exit 1; }
    python3 - "$INI" "$1" <<'PY'
import re, sys
path, value = sys.argv[1], sys.argv[2]
text = open(path).read()
new, count = re.subn(r'(?m)^Steve=\d+$', 'Steve=' + value, text)
assert count == 1, count
open(path, 'w').write(new)
PY
}

run_case() {
    local tag=$1 steve=$2
    echo "== $tag (ini Steve=$steve, no BALLANCE_MC_STEVE in the environment)"
    "$ROOT/tools/run/make-run.sh" "$GAME" >/dev/null
    sed -i 's/^Verbose = .*/Verbose = 1/; s/^SkipOpening = .*/SkipOpening = 1/' "$GAME/Bin/Player.ini"
    set_steve "$steve"
    grep -n '^Steve=' "$INI"
    ( cd "$GAME/Bin" && env BALLANCE_MC_STEVE_DEBUG=1 timeout -s KILL 26 ./Player > "$OUT/$tag.log" 2>&1 ) &
    local pid=$!
    sleep 8
    local w
    w=$(xdotool search --onlyvisible --classname Player 2>/dev/null | tail -1)
    xdotool windowactivate --sync "$w" 2>/dev/null
    xdotool windowfocus "$w" 2>/dev/null
    # Menu -> Play -> into the level, same clicks as the accepted A/B.
    xdotool mousemove --window "$w" 320 94; sleep 1; xdotool click 1; sleep 3
    xdotool mousemove --window "$w" 320 38; sleep 1; xdotool click 1; sleep 6
    ffmpeg -loglevel error -f x11grab -video_size 640x480 -i :99+480,260 -frames:v 1 -y "$OUT/$tag.png"
    wait $pid 2>/dev/null
    echo "-- level state:"; grep -a 'level state' "$OUT/$tag.log" | tail -2
    echo "-- steve:"; grep -a -E 'steve on|built:|following "' "$OUT/$tag.log" | tail -3
}

run_case on 1
run_case off 0

python3 "$ROOT/tools/steve/ini_ab_check.py" "$OUT"
