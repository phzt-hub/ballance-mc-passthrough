#!/usr/bin/env bash
# Re-apply the Ballanced submodule patches.
#
# The patches under this directory are the *only* record of the local Ballanced
# edits. Source/Player, Source/BuildingBlocks and Source/RenderEngine are git
# submodules pinned to upstream commits, so a plain `git submodule update`
# throws all of this work away. Run this script after every such update.
#
#   ./apply.sh                  # apply what is missing
#   BALLANCE_ROOT=... ./apply.sh
#
# Each patch is applied with `git apply --check` first, so running it twice is
# safe: an already-applied patch is reported and skipped. A submodule sitting on
# a commit the patch was not generated from is skipped too, rather than guessed.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${BALLANCE_ROOT:-$HOME/src/Ballanced}"
MODULES=(Source/Player Source/BuildingBlocks Source/RenderEngine Source/Managers/SdlInputManager)

base_for() {
    awk -v want="$1" '
        /^submodule:/    { mod=$2; sub(/.*\//, "", mod) }
        mod==want && /^base_commit:/ { print $2; found=1 }
        END { if (!found) exit 1 }
' "$HERE/MANIFEST.txt"
}

for mod in "${MODULES[@]}"; do
    name="$(basename "$mod")"
    patch="$HERE/$name.patch"

    [ -f "$patch" ] || { echo "SKIP $name: no $patch"; continue; }

    want="$(base_for "$name")" || { echo "SKIP $name: no base_commit in MANIFEST"; continue; }
    have="$(git -C "$ROOT/$mod" rev-parse HEAD)"
    if [ "$have" != "$want" ]; then
        echo "SKIP $name: submodule is at $have, patch was made against $want"
        echo "          (rebase by hand, or regenerate the patch from $mod)"
        continue
    fi

    if git -C "$ROOT/$mod" apply --check "$patch" 2>/dev/null; then
        git -C "$ROOT/$mod" apply "$patch"
        echo "applied $name"
    else
        echo "already $name (patch does not apply cleanly -- likely already applied)"
    fi
done

echo "done"
