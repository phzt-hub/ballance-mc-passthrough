#!/usr/bin/env bash
# Render a Ballance level file from source on Linux and save PNGs.
#
#   ./render-level-frames.sh                       # Level_01, three framings
#   ./render-level-frames.sh "3D Entities/Level/Level_02.NMO" out/
#
# How it works: tools/poc1/run-poc1.sh starts the Ballanced Player on base.cmo
# with --level, which merges the level .NMO into the composition (a level .NMO
# has no CKLevel of its own), BALLANCE_POC_ISOLATE hides the menu objects and
# BALLANCE_POC_CAM parks the eye; the depth-dump hook writes the colour buffer,
# which this script turns into a PNG.
set -euo pipefail

LEVEL=${1:-"3D Entities/Level/Level_01.NMO"}
OUTDIR=${2:-$HOME/ballance-mc-frames}
HERE=$(cd "$(dirname "$0")" && pwd)
RUN="$HERE/../poc1/run-poc1.sh"
FRAME=${FRAME:-600}

# name:px,py,pz,tx,ty,tz -- the first section, eye level, and a wide overview.
CAMS=(
  "start:   -160,80,-280, -50,-30,-500"
  "ground:  -150,30,-330, -60,10,-420"
  "over:    -500,500,-100, 200,-150,-800"
)

mkdir -p "$OUTDIR"
cd "$OUTDIR"

for entry in "${CAMS[@]}"; do
    name=${entry%%:*}
    spec=$(echo "${entry#*:}" | tr -d ' ')
    dumps="/tmp/poc1/render-$name"
    rm -rf "$dumps"
    echo "== $name  eye/target $spec"
    OUT="$dumps" RUN_SECONDS=${RUN_SECONDS:-14} FRAMES="$FRAME" \
        BALLANCE_POC_ISOLATE=1 BALLANCE_POC_CAM="$spec" \
        "$RUN" --cmo base.cmo --level "$LEVEL" >/dev/null 2>&1 || true

    python3 - "$dumps" "$name" "$OUTDIR" <<'PY'
import json, sys
import numpy as np
from pathlib import Path
from PIL import Image
dumps, name, outdir = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
bins = sorted(dumps.glob('native-color-*.bin'))
if not bins:
    raise SystemExit(f"{name}: no colour dump (frame not reached?)")
src = bins[0]
meta = json.loads(Path(str(src).replace('.bin', '.json')).read_text())
raw = np.frombuffer(src.read_bytes(), np.uint8).reshape(meta['height'], meta['width'], meta['bytes_per_pixel'])
rgb = raw[:, :, [2, 1, 0]]
target = outdir / f"{Path(sys.argv[1]).name.replace('render-', '')}.png"
Image.fromarray(rgb).save(target)
lum = rgb.astype(float).mean(axis=2)
print(f"  {target}  {meta['width']}x{meta['height']}  luminance mean={lum.mean():.1f} sd={lum.std():.1f}")
PY
done
