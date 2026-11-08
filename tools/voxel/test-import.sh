#!/usr/bin/env bash
# Acceptance for the level import: a level starts, its baked world is copied in,
# and there is a barrier block under the ball (the thing redstone needs).
#
#   ./test-import.sh [level]        default 1; any of 1..13 (past the first, the
#                                   level list is unlocked with the credits cheat)
#
# Needs a display (Xvfb :99 here), the mod built, and tools/voxel/package-templates.sh
# already run so the jar carries the templates.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LEVEL=${1:-1}
OUT=${OUT:-/tmp/voxel-test}
export DISPLAY=${DISPLAY:-:99}
rm -rf "$OUT"; mkdir -p "$OUT"

fail() { echo "FAIL: $*"; exit 1; }

# A leftover Ballance from an earlier run publishes into the same shared camera object, so the
# mod would see two writers fighting over "which level" (measured 2026-11-08: repeated level
# starts two seconds apart, and the floor check landed on the menu's ball at the origin).
pkill -x Player 2>/dev/null
pkill -f "[n]et.fabricmc.devlaunchinjector" 2>/dev/null
sleep 2

echo "== starting Minecraft"
( cd "$ROOT/mc-mod" && ./gradlew25 runClient > "$OUT/mc.log" 2>&1 ) &
MC=$!
for _ in $(seq 1 180); do
    grep -qa 'in world:' "$OUT/mc.log" && break
    sleep 1
done
grep -qa 'in world:' "$OUT/mc.log" || { pkill -f '[n]et.fabricmc.devlaunchinjector'; fail 'Minecraft never entered a world'; }
echo "   $(grep -a 'in world:' "$OUT/mc.log" | tail -1 | sed 's/.*world: //')"

echo "== starting Ballance and entering level $LEVEL"
BALLANCE_MC_PASSTHROUGH=1 BALLANCE_MC_CAMERA=1 BALLANCE_MC_STEVE=0 BALLANCE_MC_STEVE_FP=0 \
    RUN_SECONDS=200 OUT="$OUT/depth" "$ROOT/tools/poc1/run-poc1.sh" --cmo base.cmo > "$OUT/game.log" 2>&1 &
GAME=$!
for _ in $(seq 1 120); do xdotool search --name Ballance >/dev/null 2>&1 && break; sleep 0.5; done
sleep 4
if [ "$LEVEL" = 1 ]; then
    "$ROOT/tools/passthrough/enter-level.sh" 4 6 || echo "   (menu click reported a problem)"
else
    # Levels past the first are only reachable with the level list unlocked, which the
    # credits cheat does; see tools/passthrough/enter-level-n.sh.
    "$ROOT/tools/passthrough/enter-level-n.sh" "$LEVEL" 4 || echo "   (menu click reported a problem)"
fi

for _ in $(seq 1 120); do
    grep -qa 'voxels: under the ball' "$OUT/mc.log" && break
    sleep 1
done

echo "== log"
grep -aE 'voxels: level|opening the baked world|world ready|archived|under the ball|redstone dust' "$OUT/mc.log" | sed 's/^/   /'

echo "== checks"
grep -qa "voxels: level $LEVEL " "$OUT/mc.log" || fail "level $LEVEL was not identified"
grep -qa 'opening the baked world' "$OUT/mc.log" || fail 'no baked world was opened'
grep -qaE 'under the ball at .* is Block\{minecraft:barrier\}' "$OUT/mc.log" || fail 'no barrier under the ball'
echo "PASS: level $LEVEL, baked world, barrier under the ball"

kill "$GAME" 2>/dev/null
pkill -x Player 2>/dev/null
pkill -f '[n]et.fabricmc.devlaunchinjector' 2>/dev/null
wait 2>/dev/null
exit 0
