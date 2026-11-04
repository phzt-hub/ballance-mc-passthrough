#!/usr/bin/env bash
# tour-shots.sh — 拍 M0b 同屏截图：Ballance 窗口里显示实时 Minecraft 帧。
#
#   ./tools/passthrough/tour-shots.sh [tag]
#
# 与 test-m0b.sh 的区别：那是**验收**（连拍 6 张证明画面稳定），这是**取材**——
# 在 Ballance 运行期间驱动 Minecraft 换三个场景，每换一次都先核对生产者自己
# 的像素确实变了，再抓图。这样"三张一模一样"不会悄悄发生（2026-11-04 就踩过：
# Minecraft 一失焦就自动暂停，Ballance 起来抢走焦点，截出来是同一张暂停画面）。
#
# 前置：~/mcmod 的 runClient 已在跑（/dev/shm/ballance_mc_frame 存在且推进）。
# 需要：Xvfb（或 X）、xdotool、ffmpeg、python3+PIL。
#
# 坑位记录：
#   1. Minecraft 失焦会自动暂停（options.txt: pauseOnLostFocus:true），重获焦点
#      也不自动恢复。所以每次发命令都带重试：没看到画面变化就发一次 Escape。
#   2. 无窗口管理器时 SDL 会把 Ballance 窗口居中到 (480,260)，要等它落位再挪。
#   3. pkill -f "http.server" 这类模式会匹配到调用者自己的命令行，别在同一个
#      bash -c 里做。
set -uo pipefail

TAG=${1:-tour}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${OUT:-/tmp/shots/$TAG}
export DISPLAY=${DISPLAY:-:99}
SHM=/dev/shm/ballance_mc_frame

cd "$ROOT"
rm -rf "$OUT"; mkdir -p "$OUT" /tmp/probe
[ -e "$SHM" ] || { echo "没有生产者：$SHM 不存在（runClient 在跑吗？）"; exit 1; }

geom() {
    xwininfo -id "$1" 2>/dev/null | awk '
        /Absolute upper-left X/ {print "X=" $4}
        /Absolute upper-left Y/ {print "Y=" $4}
        /Width:/ {print "W=" $2}
        /Height:/ {print "H=" $2}'
}
place() {
    local wid=$1 px=$2 py=$3 t=0
    while [ $t -lt 6 ]; do
        xdotool windowmove "$wid" "$px" "$py" 2>/dev/null; sleep 0.4
        eval "$(geom "$wid")"
        [ "${X:-x}" = "$px" ] && [ "${Y:-x}" = "$py" ] && return 0
        t=$((t + 1))
    done
    return 1
}
# 生产者自己的帧（邮箱里的 slot，不是屏幕抓图）——判定 Minecraft 那边到底变没变。
snap() {
    python3 - "$1" <<'PY'
import mmap, struct, sys
from PIL import Image
f = open('/dev/shm/ballance_mc_frame', 'rb')
m = mmap.mmap(f.fileno(), 0, prot=mmap.PROT_READ)
w, h = struct.unpack_from('<II', m, 8)
slot = struct.unpack_from('<I', m, 36)[0]
off = struct.unpack_from('<Q', m, 56 + 8 * slot)[0]
buf = m[off:off + w * 4 * h]
Image.frombuffer('RGBA', (w, h), buf, 'raw', 'BGRA', w * 4, 1).convert('RGB').save(sys.argv[1])
PY
}
pct() {
    python3 - "$1" "$2" <<'PY'
import sys
from PIL import Image, ImageChops
a = Image.open(sys.argv[1]).convert('RGB'); b = Image.open(sys.argv[2]).convert('RGB')
h = ImageChops.difference(a, b).convert('L').histogram(); tot = sum(h)
print(f"{100 * (tot - sum(h[:9])) / tot:.1f}")
PY
}

MCWID=$(xwininfo -root -tree 2>/dev/null | grep -i Minecraft | grep -oE '0x[0-9a-f]+' | head -1)
[ -n "$MCWID" ] || { echo "没有 Minecraft 窗口"; exit 1; }
place "$MCWID" 746 520
focusmc() { xdotool windowfocus "$MCWID" 2>/dev/null; sleep 0.5; }

# 发一条聊天命令，直到画面确实变了（没变就 Escape 一次重试，见坑位 1）。
cmd() {
    local c=$1 b=/tmp/probe/before.png a=/tmp/probe/after.png p
    for attempt in 1 2 3; do
        snap $b
        focusmc
        xdotool key t; sleep 0.8
        xdotool type --delay 30 "$c"; sleep 0.6
        xdotool key Return; sleep 2.5
        snap $a
        p=$(pct $b $a)
        echo "   命令 \"$c\" 第 $attempt 次，画面变化 ${p}%" >&2
        awk -v p="$p" 'BEGIN{exit !(p > 4)}' && return 0
        xdotool key Escape; sleep 1.5
    done
    echo "   !! 命令 \"$c\" 没有可见效果" >&2
    return 1
}

focusmc
cmd "/time set day" || true
cmd "/weather clear" || true

python3 tools/passthrough/inspect-mailbox.py --watch 3 --png "$OUT/mc-frame.png" > "$OUT/mailbox.json" 2>&1

OUT="$OUT" RUN_SECONDS=80 FRAMES="" \
    BALLANCE_MC_PASSTHROUGH=1 BALLANCE_POC_FRAMELOG=200 \
    ./tools/poc1/run-poc1.sh --cmo base.cmo --verbose > "$OUT/game.log" 2>&1 &
GAME=$!

WID=""
for _ in $(seq 1 40); do
    WID=$(xwininfo -root -tree 2>/dev/null | grep -E '640x480' | grep -v Minecraft \
        | grep -oE '0x[0-9a-f]+' | tr -d ' ' | head -1)
    [ -n "$WID" ] && break
    sleep 0.5
done
[ -n "$WID" ] || { echo "没有 Ballance 窗口"; wait "$GAME"; exit 1; }
sleep 2.5
place "$WID" 0 0
eval "$(geom "$WID")"
echo "== Ballance 窗口 $WID 位于 ${X},${Y} ${W}x${H}"
grab() {
    ffmpeg -loglevel error -f x11grab -video_size "${W}x${H}" -i "${DISPLAY}+${X},${Y}" \
        -frames:v 1 -y "$1" 2>/dev/null
}

# --- 场景 1：原地不动，第一人称，正午晴天
focusmc
cmd "/time set day" || true
sleep 2.5
grab "$OUT/shot1.png"; snap /tmp/probe/s1.png
echo "-- shot1：白天 · 第一人称"

# --- 场景 2：第三人称，转到另一个朝向
focusmc
xdotool key F5; sleep 1.2
xdotool mousemove $((746 + 427)) $((520 + 240)); sleep 0.2
xdotool click 1; sleep 0.3
xdotool mousemove_relative -- 420 60; sleep 2.0
snap /tmp/probe/s2.png
echo "-- shot2：第三人称 · 转视角（生产者侧变化 $(pct /tmp/probe/s1.png /tmp/probe/s2.png)%）"
grab "$OUT/shot2.png"

# --- 场景 3：向前走几步，日落光
focusmc
xdotool keydown w; sleep 2.2; xdotool keyup w; sleep 0.8
cmd "/time set 13000" || true
sleep 4
snap /tmp/probe/s3.png
echo "-- shot3：日落 · 前行（生产者侧变化 $(pct /tmp/probe/s2.png /tmp/probe/s3.png)%）"
grab "$OUT/shot3.png"

wait "$GAME" 2>/dev/null
echo "== 抓图 $(ls "$OUT"/shot*.png 2>/dev/null | wc -l) 张：$OUT"
echo "== 相邻两张差异："
printf '   shot1 vs shot2  %s%%\n   shot2 vs shot3  %s%%\n' \
    "$(pct "$OUT/shot1.png" "$OUT/shot2.png")" "$(pct "$OUT/shot2.png" "$OUT/shot3.png")"
grep -a "mc-passthrough" "$OUT/game.log" | tail -2
