#!/usr/bin/env bash
# Ship the Ballanced sources (and the Minecraft mod jar) from this Linux host to a
# Windows box, and drive the build there.
#
# Windows never owns source. Everything under D:\bmc-build\Ballanced is a copy of
# ~/src/Ballanced taken from here, so a fix made "for Windows" is a fix in this tree
# and can be rebuilt on any Windows machine - just point BMC_HOST at it:
#
#   ./tools/windows-sync.sh --build --jar      the normal push to 192.168.3.128
#   ./tools/windows-sync.sh                    pack, upload, extract, prune (no build)
#   ./tools/windows-sync.sh --jar              ... also push the mod jar
#   ./tools/windows-sync.sh --build            ... also start the configure+build+deploy task
#   ./tools/windows-sync.sh --status           report the host, change nothing
#
# The build itself is D:\bmc-build\rebuild-pt.cmd (kept in tools/windows/): it runs
# configure -> build -> robocopy into the playable runtime and writes the log to
# D:\bmc-build\rebuild-pt.log. It is started through the scheduled task bmc-pt,
# because a build started inside an ssh session dies with the session.
#
# Env: BMC_HOST   win128            (ssh alias: Administrator, key ~/.ssh/win128_ctl_ed25519)
#      BMC_ROOT   D:/bmc-build
#      BMC_PLAY   J:/bmc-project/runtime
#      BMC_SRC    ~/src/Ballanced
#      BMC_TASK   bmc-pt
#      BMC_TARBALL /tmp/ballanced-src.tgz
set -euo pipefail

HOST=${BMC_HOST:-win128}
WIN_ROOT=${BMC_ROOT:-D:/bmc-build}
WIN_PLAY=${BMC_PLAY:-J:/bmc-project/runtime}
SRC=${BMC_SRC:-$HOME/src/Ballanced}
TASK=${BMC_TASK:-bmc-pt}
TARBALL=${BMC_TARBALL:-/tmp/ballanced-src.tgz}
REPO=$(cd "$(dirname "$0")/.." && pwd)
JAR=$REPO/mc-mod/build/libs/ballance_passthrough-1.0.0.jar
WIN_DIR=$REPO/tools/windows
RUNNER=$REPO/tools/win128-run.sh

DO_BUILD=0
DO_JAR=0
STATUS=0

for arg in "$@"; do
    case "$arg" in
        --build) DO_BUILD=1 ;;
        --jar) DO_JAR=1 ;;
        --status) STATUS=1 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

bs() { printf '%s' "$1" | tr '/' '\\'; }
rp() { printf '/%s' "$1"; }

if [ "$STATUS" = 1 ]; then
    echo "== host $HOST"
    ssh -o ConnectTimeout=10 "$HOST" "cmd /c \"dir /b $(bs "$WIN_ROOT")\""
   echo "== playable"
    ssh -o ConnectTimeout=10 "$HOST" "cmd /c \"dir $(bs "$WIN_PLAY")\\Bin\\Player.exe & dir $(bs "$WIN_PLAY")\\RenderEngines\\CKSdlGpuRasterizer.dll & dir $(bs "$WIN_PLAY")\\mc\\mods\""
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

echo "== list of files that must survive on the Windows copy"
(cd "$SRC/Source" && find . -type f | sed 's|^\./||' | tr '/' '\\') > /tmp/keep-source.txt
scp -q -o ConnectTimeout=10 /tmp/keep-source.txt "$HOST:$(rp "$WIN_ROOT")/keep-source.txt"
scp -q -o ConnectTimeout=10 "$WIN_DIR/prune-stale.ps1" "$HOST:$(rp "$WIN_ROOT")/prune-stale.ps1"

echo "== extracting into $(bs "$WIN_ROOT")"
"$RUNNER" <<EOF
cd /d $(bs "$WIN_ROOT")
tar xzf $(basename "$TARBALL")
if errorlevel 1 echo TAR_FAILED
echo == prune
powershell -NoProfile -ExecutionPolicy Bypass -File $(bs "$WIN_ROOT")\prune-stale.ps1
EOF

if [ "$DO_JAR" = 1 ]; then
    [ -f "$JAR" ] || { echo "no jar at $JAR - build it first (cd mc-mod && ./gradlew25 build)" >&2; exit 1; }
    echo "== uploading $(basename "$JAR")"
    scp -o ConnectTimeout=10 "$JAR" "$HOST:$(rp "$WIN_PLAY")/mc/mods/"
fi

if [ "$DO_BUILD" = 1 ]; then
    echo "== deploying the build script and starting $TASK"
    scp -o ConnectTimeout=10 "$WIN_DIR/rebuild-pt.cmd" "$HOST:$(rp "$WIN_ROOT")/rebuild-pt.cmd"
    "$RUNNER" <<EOF
schtasks /run /tn $TASK
echo started
EOF
    echo "   log: $HOST:$(bs "$WIN_ROOT")\\rebuild-pt.log"
fi

echo "== done"
