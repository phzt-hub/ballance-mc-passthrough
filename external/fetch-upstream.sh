#!/bin/sh
# 拉取上游源码到 ../Ballanced 与 ../CKRenderEngine。
# 上游不随本仓库分发：代码量大、且持续变动；LICENSE 分别是 MIT 与 Apache-2.0。
set -e
DST=${1:-..}

if [ ! -d "$DST/Ballanced" ]; then
    git clone --depth 1 -b sdl https://github.com/doyaGu/Ballanced "$DST/Ballanced"
fi
git -C "$DST/Ballanced" submodule update --init --depth 1

if [ ! -d "$DST/CKRenderEngine" ]; then
    git clone --depth 1 -b sdl https://github.com/doyaGu/CKRenderEngine "$DST/CKRenderEngine"
fi

echo "done: $DST/Ballanced ($(git -C "$DST/Ballanced" log -1 --format=%h))"
