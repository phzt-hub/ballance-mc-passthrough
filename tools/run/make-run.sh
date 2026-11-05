#!/usr/bin/env bash
# Materialise the game tree the Player binary needs, without launching it.
#
#   ./make-run.sh [rundir]        # default: <repo>/run/game
#
# Two halves, deliberately joined with symlinks:
#   * every module/bin/lib comes from the Ballanced install tree (the stage),
#     because those are ours to rebuild;
#   * base.cmo / Database.tdb / 3D Entities / Textures / Sounds / Text / help
#     come from the retail install, because they are the game's own assets and
#     must never be copied into a git repository.
# Result: ~/src/ballance-mc-passthrough/run/game, i.e. inside the repo but
# git-ignored, and recreated in seconds after a wipe. /tmp used to hold it and
# /tmp is tmpfs on this machine.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BALLANCE_ROOT=${BALLANCE_ROOT:-$HOME/src/Ballanced}
ASSETS=${ASSETS:-$HOME/src/ballance-game}
STAGE=${STAGE:-$BALLANCE_ROOT/build/linux-x64-runtime/stage}
GAME=${1:-${GAME:-$ROOT/run/game}}

[ -x "$STAGE/Bin/Player" ] || { echo "no Player in $STAGE; run tools/poc1/build-ballanced.sh first" >&2; exit 1; }
[ -f "$ASSETS/base.cmo" ]   || { echo "no game assets in $ASSETS" >&2; exit 1; }

mkdir -p "$GAME"
if command -v rsync >/dev/null; then
    # Minecraft.ini is the player's own settings file (written by the in-game
    # panel); the stage does not carry it, so --delete would wipe it on every
    # launch and the panel would look like it forgets.
    rsync -a --delete --exclude=Bin/Minecraft.ini "$STAGE/" "$GAME/"
else
    rm -rf "$GAME"; mkdir -p "$GAME"; cp -a "$STAGE/." "$GAME/"
fi

for dir in "3D Entities" Textures Sounds Text help; do ln -sfn "$ASSETS/$dir" "$GAME/$dir"; done
for file in base.cmo Database.tdb; do ln -sfn "$ASSETS/$file" "$GAME/$file"; done

# The opening sequence renders for a minute before the menu; skip it.
[ -f "$GAME/Bin/Player.ini" ] && sed -i 's/^SkipOpening = .*/SkipOpening = 1/' "$GAME/Bin/Player.ini"

echo "$GAME"
