#!/usr/bin/env bash
# Build nmo_dump against an existing Ballanced CK2 build tree.
#   BALLANCED_DIR   default $HOME/src/Ballanced
#   CK2_BUILD_DIR   default /tmp/ck2build  (cmake -S Source/CK2 -B ... -G Ninja)
set -euo pipefail
BALLANCED_DIR="${BALLANCED_DIR:-$HOME/src/Ballanced}"
CK2_BUILD_DIR="${CK2_BUILD_DIR:-/tmp/ck2build}"
OUT="${1:-/tmp/nmo_dump}"
g++ -std=c++17 -O2 \
  -I "$BALLANCED_DIR/Source/CK2/include" \
  -I "$BALLANCED_DIR/Source/VxMath/include" \
  "$(dirname "$0")/nmo_dump.cpp" \
  -L "$CK2_BUILD_DIR/lib" -lCK2 -Wl,-rpath,"$CK2_BUILD_DIR/lib" \
  -o "$OUT"
echo "built $OUT"
