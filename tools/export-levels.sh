#!/usr/bin/env bash
# Export every Ballance level (and the asset files the calibration table needs)
# to plain OBJ + JSON manifests with nmo_geom.
#
# Usage: export-levels.sh <nmo_geom binary> <game dir> <output dir>
#   nmo_geom     built by tools/build-nmo-geom.sh
#   game dir     e.g. ~/src/ballance-game      ("3D Entities/" is read under it)
#   output dir   e.g. /tmp/nmg                 (products are not committed)
set -e

BIN="$1"
GAME="$2"
OUT="$3"
if [ -z "$BIN" ] || [ -z "$GAME" ] || [ -z "$OUT" ]; then
  echo "usage: export-levels.sh <nmo_geom> <game dir> <output dir>" >&2
  exit 2
fi

mkdir -p "$OUT/levels" "$OUT/ph" "$OUT/misc"

for f in "$GAME/3D Entities/Level"/Level_*.NMO; do
  b=$(basename "$f" .NMO)
  "$BIN" "$f" --obj "$OUT/levels/$b.obj" --json "$OUT/levels/$b.json" 2>>"$OUT/levels/_log.txt"
done

for f in "$GAME/3D Entities/PH"/*.nmo; do
  b=$(basename "$f" .nmo)
  "$BIN" "$f" --json "$OUT/ph/$b.json" 2>>"$OUT/ph/_log.txt"
done

for f in "$GAME/3D Entities"/*.nmo; do
  b=$(basename "$f" .nmo)
  "$BIN" "$f" --json "$OUT/misc/$b.json" 2>>"$OUT/misc/_log.txt"
done

echo "levels: $(ls "$OUT/levels" | grep -c json)  ph: $(ls "$OUT/ph" | grep -c json)  misc: $(ls "$OUT/misc" | grep -c json)"
