#!/usr/bin/env bash
# Bake the 13 levels into world saves, once, with the game itself.
#
#   ./bake-levels.sh                 # all 13
#   ./bake-levels.sh 1,8             # just these
#
# Each level is written by the mod's BALLANCE_MC_BAKE mode into a throw-away void
# world (bmc-tpl-NN), which is then moved to run/templates/. tools/voxel/package-templates.sh
# zips those into the mod's resources, and from then on a level start is a copy.
#
# Why the game and not an offline Anvil writer: the save format belongs to whatever
# Minecraft the mod is built against, and letting the game write it is the only way
# to be sure the file matches.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export DISPLAY=${DISPLAY:-:99}
LEVELS=${1:-1,2,3,4,5,6,7,8,9,10,11,12,13}
TIMEOUT=${TIMEOUT:-420}

cd "$ROOT/mc-mod" || exit 1
mkdir -p "$ROOT/run/templates" "$ROOT/run/bake-logs"

for n in $(echo "$LEVELS" | tr ',' ' '); do
    id=$(printf "bmc-tpl-%02d" "$n")
    log="$ROOT/run/bake-logs/$id.log"
    rm -rf "run/saves/$id" "$ROOT/run/templates/$id"
    echo "== baking level $n -> $id"
    BALLANCE_MC_BAKE="$n" BALLANCE_MC_VOID_WORLD="$id" \
        ./gradlew25 runClient > "$log" 2>&1 &
    gradle_pid=$!
    done_ok=0
    for _ in $(seq 1 "$TIMEOUT"); do
        if grep -qa "BAKE DONE" "$log"; then done_ok=1; break; fi
        if ! kill -0 "$gradle_pid" 2>/dev/null; then break; fi
        sleep 1
    done
    sleep 3
    pkill -f "[n]et.fabricmc.devlaunchinjector" 2>/dev/null
    wait "$gradle_pid" 2>/dev/null
    sleep 2
    if [ "$done_ok" = 1 ] && [ -f "run/saves/$id/level.dat" ]; then
        mv "run/saves/$id" "$ROOT/run/templates/$id"
        echo "   ok: $(du -sh "$ROOT/run/templates/$id" | cut -f1)  $(grep -a 'BAKE DONE' "$log" | tail -1 | sed 's/.*voxels: //')"
    else
        echo "   FAILED: no BAKE DONE in $log"
        tail -5 "$log"
    fi
done
