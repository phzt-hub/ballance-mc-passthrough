#!/usr/bin/env bash
# Zip the baked level worlds into the mod's resources.
#
#   ./package-templates.sh
#       run/templates/bmc-tpl-NN  ->  assets/ballance/templates/Level_NN.zip
#
# The mod unpacks one of these into saves/<id> and opens it (McWorldBootstrap.openLevelWorld),
# so a level start is a file copy instead of a block-by-block import.
#
# Uses python's zipfile rather than the zip CLI: this host has unzip but not zip.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
python3 - "$ROOT" <<'PY'
import sys, zipfile
from pathlib import Path

root = Path(sys.argv[1])
src = root / "run" / "templates"
out = root / "mc-mod" / "src" / "main" / "resources" / "assets" / "ballance" / "templates"
out.mkdir(parents=True, exist_ok=True)

count = 0
for world in sorted(src.glob("bmc-tpl-*")):
    if not (world / "level.dat").is_file():
        print(f"  skip {world.name}: no level.dat")
        continue
    level = world.name.split("-")[-1]
    target = out / f"Level_{level}.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for path in sorted(world.rglob("*")):
            if path.is_dir():
                continue
            zf.write(path, path.relative_to(world).as_posix())
    count += 1
    print(f"  Level_{level}  {target.stat().st_size / 1024 / 1024:6.1f} MB  "
          f"{len(zipfile.ZipFile(target).namelist())} files")

print(f"packaged {count} template(s) into {out}")
PY
