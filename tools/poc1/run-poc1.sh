#!/usr/bin/env bash
# Assemble a runnable Ballance directory from the Ballanced stage plus the
# original game assets, run it headless on Xvfb, and dump depth frames.
#
#   FRAMES=600,1200 ./run-poc1.sh          # dump these submission numbers
#   FRAMES='' ./run-poc1.sh                # just run, no dumps
#   ./run-poc1.sh extra-args-for-Player
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)

BALLANCE_ROOT=${BALLANCE_ROOT:-$HOME/src/Ballanced}
ASSETS=${ASSETS:-$HOME/src/ballance-game}
STAGE=${STAGE:-$BALLANCE_ROOT/build/linux-x64-runtime/stage}
# Runs live inside the repo (git-ignored) instead of /tmp: /tmp is tmpfs
# here, so a reboot used to take the directory with it.
GAME=${GAME:-$ROOT/run/game}
OUT=${OUT:-$ROOT/run/out}
FRAMES=${FRAMES-600,1200,1800}
RUN_SECONDS=${RUN_SECONDS:-45}
DISPLAY=${DISPLAY:-:99}
export DISPLAY

[ -x "$STAGE/Bin/Player" ] || { echo "no Player in $STAGE; run build-ballanced.sh first" >&2; exit 1; }
[ -f "$ASSETS/base.cmo" ] || { echo "no game assets in $ASSETS" >&2; exit 1; }

mkdir -p "$GAME" "$OUT"
if command -v rsync >/dev/null; then
    # Minecraft.ini is the player's own settings file (written by the in-game
    # panel); the stage does not carry it, so --delete would wipe it on every
    # launch and the panel would look like it forgets.
    rsync -a --delete --exclude=Bin/Minecraft.ini "$STAGE/" "$GAME/"
else
    rm -rf "$GAME"; mkdir -p "$GAME"; cp -a "$STAGE/." "$GAME/"
fi
# Original assets come from the game install; the stage owns the modules, so we
# only borrow the data folders (never the game's 32-bit Windows DLLs).
for dir in "3D Entities" Textures Sounds Text help; do ln -sfn "$ASSETS/$dir" "$GAME/$dir"; done
for file in base.cmo Database.tdb; do ln -sfn "$ASSETS/$file" "$GAME/$file"; done

# The opening sequence renders for a minute before the menu; skip it.
[ -f "$GAME/Bin/Player.ini" ] && sed -i 's/^SkipOpening = .*/SkipOpening = 1/' "$GAME/Bin/Player.ini"

echo "== running on DISPLAY=$DISPLAY, dumps -> $OUT"
cd "$GAME/Bin"
BALLANCE_POC1_DEPTH_FRAMES="$FRAMES" BALLANCE_POC1_DEPTH_DIR="$OUT" \
    timeout -s KILL "$RUN_SECONDS" ./Player "$@" || true
echo "== dumps: $(ls "$OUT" 2>/dev/null | wc -l) file(s)"
