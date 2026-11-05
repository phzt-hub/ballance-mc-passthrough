#!/usr/bin/env bash
# Ship the Ballanced sources (and the Minecraft mod jar) from this Linux host to a
# Windows box, and drive the build there.
#
# Windows never owns source. Everything under D:\bmc-build\Ballanced is a copy of
# ~/src/Ballanced taken from here, so a fix made "for Windows" is a fix in this tree
# and can be rebuilt on any Windows machine - just point BMC_HOST at it:
#
#   BMC_HOST=Administrator@10.0.0.9 ./tools/windows-sync.sh --build --jar --deploy
#
#   ./tools/windows-sync.sh              pack, upload, extract (nothing else)
#   ./tools/windows-sync.sh --build      ... then start the scheduled build
#   ./tools/windows-sync.sh --jar        ... then push the mod jar into mc\mods
#   ./tools/windows-sync.sh --deploy     ... then copy stage -> playable dir
#   ./tools/windows-sync.sh --deploy --reset-ini   (also delete Bin\Minecraft.ini)
#   ./tools/windows-sync.sh --status     report the host, change nothing
#
# Env: BMC_HOST   Administrator@10.0.0.4
#      BMC_ROOT   D:/bmc-build
#      BMC_PLAY   D:/bmc-play
#      BMC_SRC    ~/src/Ballanced
#      BMC_TASK   bmc-build  (scheduled task name)
#      BMC_TARBALL /tmp/ballanced-src.tgz
set -euo pipefail

HOST=${BMC_HOST:-Administrator@10.0.0.4}
WIN_ROOT=${BMC_ROOT:-D:/bmc-build}
WIN_PLAY=${BMC_PLAY:-D:/bmc-play}
SRC=${BMC_SRC:-$HOME/src/Ballanced}
TASK=${BMC_TASK:-bmc-build}
TARBALL=${BMC_TARBALL:-/tmp/ballanced-src.tgz}
REPO=$(cd "$(dirname "$0")/.." && pwd)
JAR=$REPO/mc-mod/build/libs/ballance_passthrough-1.0.0.jar
WIN_DIR=$REPO/tools/windows

DO_BUILD=0
DO_JAR=0
DO_DEPLOY=0
RESET_INI=0
STATUS=0

for arg in "$@"; do
    case "$arg" in
        --build) DO_BUILD=1 ;;
        --jar) DO_JAR=1 ;;
        --deploy) DO_DEPLOY=1 ;;
        --reset-ini) RESET_INI=1 ;;
        --status) STATUS=1 ;;
        -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# cmd.exe wants backslashes, scp wants the drive as a path component (/D:/bmc-build).
bs() { printf '%s' "$1" | tr '/' '\\'; }
rp() { printf '/%s' "$1"; }
xssh() { ssh -o ConnectTimeout=10 "$HOST" "cmd /c \"$1\""; }

if [ "$STATUS" = 1 ]; then
    echo "== host $HOST"
    xssh "dir /b $(bs "$WIN_ROOT")"
    echo "== source in use"
    xssh "dir $(bs "$WIN_ROOT")\\Ballanced\\Source\\RenderEngine\\src\\CKRasterizer\\CKSdlGpuRasterizer\\Rasterizer\\CKSdlGpuRasterizerPassthrough.cpp | findstr CKSdl"
    echo "== built rasterizer"
    xssh "dir $(bs "$WIN_ROOT")\\stage\\RenderEngines\\CKSdlGpuRasterizer.dll | findstr CKSdl"
    echo "== playable rasterizer"
    xssh "dir $(bs "$WIN_PLAY")\\RenderEngines\\CKSdlGpuRasterizer.dll | findstr CKSdl"
    echo "== mod jar"
    xssh "dir /b $(bs "$WIN_PLAY")\\mc\\mods"
    exit 0
fi

echo "== packing $SRC"
# build/ and .git stay behind: Windows only compiles, it never commits.
tar czf "$TARBALL" --exclude='build' --exclude='.git' \
    -C "$(dirname "$SRC")" "$(basename "$SRC")"
ls -lh "$TARBALL"
md5sum "$TARBALL"

echo "== uploading to $HOST:$(rp "$WIN_ROOT")/"
scp -o ConnectTimeout=10 "$TARBALL" "$HOST:$(rp "$WIN_ROOT")/"

echo "== extracting into $(bs "$WIN_ROOT")"
xssh "cd /d $(bs "$WIN_ROOT") & tar xzf $(basename "$TARBALL") & echo extract ok"

if [ "$DO_JAR" = 1 ]; then
    [ -f "$JAR" ] || { echo "no jar at $JAR - build it first (cd mc-mod && ./gradlew25 build)" >&2; exit 1; }
    echo "== uploading $(basename "$JAR")"
    scp -o ConnectTimeout=10 "$JAR" "$HOST:$(rp "$WIN_PLAY")/mc/mods/"
fi

if [ "$DO_DEPLOY" = 1 ]; then
    echo "== deploying stage -> playable directory"
    scp -o ConnectTimeout=10 "$WIN_DIR"/deploy.bat "$WIN_DIR"/run-ballance-mc.bat \
        "$WIN_DIR"/run-ballance-plain.bat "$HOST:$(rp "$WIN_PLAY")/"
    if [ "$RESET_INI" = 1 ]; then
        xssh "$(bs "$WIN_PLAY")\\deploy.bat $(bs "$WIN_ROOT")\\stage $(bs "$WIN_PLAY") --reset-ini"
    else
        xssh "$(bs "$WIN_PLAY")\\deploy.bat $(bs "$WIN_ROOT")\\stage $(bs "$WIN_PLAY")"
    fi
fi

if [ "$DO_BUILD" = 1 ]; then
    echo "== starting the scheduled build ($TASK)"
    # A build started inside the ssh session is killed when the session closes, so it
    # has to detach through the task scheduler (docs/windows-build.md, pitfall 1).
    xssh "schtasks /create /tn $TASK /tr $(bs "$WIN_ROOT")\\run.bat /sc once /st 23:59 /ru SYSTEM /rl HIGHEST /f & schtasks /run /tn $TASK & echo build started"
    echo "   log: $HOST:\\$(bs "$WIN_ROOT")\\build.log"
fi

echo "== done"
