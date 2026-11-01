#!/usr/bin/env bash
# Build Ballanced's Linux x64 runtime (SDL3 GPU backend) and stage it.
#
# PoC-1 depends on a specific minimum SDL3: Ballanced asks for >= 3.4.8, Debian
# ships 3.2.10, so this script builds SDL3 into ~/.local/opt once and points
# CMake at it. Nothing is installed system-wide.
set -euo pipefail

SDL_VERSION=${SDL_VERSION:-3.4.18}
SDL_PREFIX=${SDL_PREFIX:-$HOME/.local/opt/sdl3-$SDL_VERSION}
BALLANCE_ROOT=${BALLANCE_ROOT:-$HOME/src/Ballanced}
WORK=${WORK:-/tmp/poc1}
JOBS=${JOBS:-$(nproc)}

if [ ! -f "$SDL_PREFIX/lib/cmake/SDL3/SDL3Config.cmake" ]; then
    echo "== building SDL3 $SDL_VERSION into $SDL_PREFIX"
    mkdir -p "$WORK/dl"
    cd "$WORK/dl"
    [ -f "SDL3-$SDL_VERSION.tar.gz" ] || \
        curl -sSL -o "SDL3-$SDL_VERSION.tar.gz" \
            "https://github.com/libsdl-org/SDL/releases/download/release-$SDL_VERSION/SDL3-$SDL_VERSION.tar.gz"
    rm -rf "SDL3-$SDL_VERSION"
    tar xf "SDL3-$SDL_VERSION.tar.gz"
    cd "SDL3-$SDL_VERSION"
    # SDL_X11_XTEST=OFF: libxtst headers are not installed here and XTEST is only
    # used to inject synthetic input, which the PoC does not need.
    cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$SDL_PREFIX" \
        -DSDL_SHARED=ON -DSDL_STATIC=ON -DSDL_TESTS=OFF -DSDL_EXAMPLES=OFF \
        -DSDL_INSTALL_TESTS=OFF -DSDL_X11_XTEST=OFF
    cmake --build build -j "$JOBS"
    cmake --install build
fi

echo "== building Ballanced runtime"
cd "$BALLANCE_ROOT"
cmake --preset linux-x64-runtime -DCMAKE_PREFIX_PATH="$SDL_PREFIX"
cmake --build --preset linux-x64-runtime-stage-release -j "$JOBS"

echo "== stage: $BALLANCE_ROOT/build/linux-x64-runtime/stage"
ls "$BALLANCE_ROOT/build/linux-x64-runtime/stage/Bin"
