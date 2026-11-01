#!/usr/bin/env bash
# Build nmo_geom against libcmo21's static library.
#
# The flag set below is copied from libcmo21's own ninja build (build.ninja FLAGS/DEFINES).
# The yycc detector macros are NOT optional: without OS / compiler / endian / pointer-size /
# charconv-probe defines the yycc headers stop on #error, and without -std=c++23 the
# char8_t / u8string code does not compile. Three traps recorded in docs/level-parse.md.
#
# Usage: build-nmo-geom.sh [OUT] [LIBCMO_SRC] [LIBCMO_BUILD] [YYC_INSTALL]
#   defaults: /tmp/nmo_geom  $HOME/src/libcmo21  /tmp/libcmo21-build  $HOME/src/yyc-install
#
# Prerequisite (once):
#   cmake -S "$HOME/src/libcmo21" -B /tmp/libcmo21-build -G Ninja \
#         -DYYCCommonplace_DIR="$HOME/src/yyc-install/lib/cmake/YYCCommonplace"
#   ninja -C /tmp/libcmo21-build LibCmo
set -e

OUT="$1"
SRC="$2"
BUILD="$3"
YYC="$4"
if [ -z "$OUT" ]; then OUT=/tmp/nmo_geom; fi
if [ -z "$SRC" ]; then SRC="$HOME/src/libcmo21"; fi
if [ -z "$BUILD" ]; then BUILD=/tmp/libcmo21-build; fi
if [ -z "$YYC" ]; then YYC="$HOME/src/yyc-install"; fi

DEF=""
DEF="$DEF -DLIBCMO_BUILD_RELEASE -DYYCC_CC_GCC -DYYCC_OS_LINUX -DYYCC_ENDIAN_LITTLE"
DEF="$DEF -DYYCC_PTRSIZE_64 -DYYCC_FEAT_ICONV"
DEF="$DEF -DYYCC_CHARCONV_HAS_CHARS_FORMAT -DYYCC_CHARCONV_HAS_FROM_CHARS_FLOAT"
DEF="$DEF -DYYCC_CHARCONV_HAS_FROM_CHARS_INT -DYYCC_CHARCONV_HAS_FROM_CHARS_RESULT"
DEF="$DEF -DYYCC_CHARCONV_HAS_TO_CHARS_FLOAT -DYYCC_CHARCONV_HAS_TO_CHARS_INT"
DEF="$DEF -DYYCC_CHARCONV_HAS_TO_CHARS_RESULT"

HERE=$(dirname "$0")
g++ -O2 -DNDEBUG -std=c++23 $DEF \
  -I "$SRC/LibCmo" \
  -isystem "$YYC/include" \
  "$HERE/nmo_geom.cpp" \
  "$BUILD/LibCmo/LibCmo.a" \
  "$YYC/lib/libYYCCommonplace.a" \
  -lz \
  -o "$OUT"
echo "built $OUT"
