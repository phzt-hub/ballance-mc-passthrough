#!/usr/bin/env bash
# Sweep PoC-2: spawn N synthetic box colliders in a bare physics scene and log
# the per-frame simulate() cost.   ./run-poc2.sh 0 100 1000 4000
set -u
export PATH=$HOME/.local/bin:$PATH
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
RUN_SECONDS=${RUN_SECONDS:-15}
for N in "$@"; do
    pkill -x Player >/dev/null 2>&1; sleep 0.5
    echo "== N=$N"
    BALLANCE_POC2_LOG=1 BALLANCE_POC2_SYNTH="$N" BALLANCE_POC2_TAG="sweep$N" \
        BALLANCE_POC2_SYNTH_AT=${SYNTH_AT:-2.0} RUN_SECONDS="$RUN_SECONDS" FRAMES= \
        "$REPO/tools/poc1/run-poc1.sh" --unlock-framerate > "/tmp/poc2probe-sweep$N.log" 2>&1
    grep -E "synth:" "/tmp/poc2probe-sweep$N.log" | tail -3
done
pkill -x Player >/dev/null 2>&1
echo "csv -> /tmp/poc2/physics-sweep<N>.csv"
