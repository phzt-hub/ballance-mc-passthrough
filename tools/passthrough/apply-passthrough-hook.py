#!/usr/bin/env python3
"""Install the Ballance <-> Minecraft frame passthrough into the Ballanced tree.

Idempotent: every edit is either already present or applied exactly once. The
sources live in this directory and are copied into the engine, because the
rasterizer context they hook is part of the Ballanced build.

    python3 tools/passthrough/apply-passthrough-hook.py [--revert]

What it touches:
  Source/RenderEngine/src/CKRasterizer/CKSdlGpuRasterizer/
      Rasterizer/mpframe.h                          (copied)
      Rasterizer/CKSdlGpuPassthrough.h              (copied)
      Rasterizer/CKSdlGpuRasterizerPassthrough.cpp  (copied)
      CMakeLists.txt                                (source + header lists)
      Rasterizer/CKSdlGpuRasterizerContext.h        (include, friend, member)
      Rasterizer/CKSdlGpuRasterizerFrame.cpp        (tick call in the overlay phase)
"""

import argparse
import pathlib
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
BALLANCED = pathlib.Path.home() / "src" / "Ballanced"
RASTER = BALLANCED / "Source/RenderEngine/src/CKRasterizer/CKSdlGpuRasterizer"
CONTEXT_H = RASTER / "Rasterizer/CKSdlGpuRasterizerContext.h"
FRAME_CPP = RASTER / "Rasterizer/CKSdlGpuRasterizerFrame.cpp"
CMAKE = RASTER / "CMakeLists.txt"

COPIES = [
    ("mpframe.h", "Rasterizer/mpframe.h"),
    ("sdlgpu-passthrough.h", "Rasterizer/CKSdlGpuPassthrough.h"),
    ("sdlgpu-passthrough.cpp", "Rasterizer/CKSdlGpuRasterizerPassthrough.cpp"),
]

INCLUDE_ANCHOR = '#include "CKSdlGpuPresentStage.h"\n'
INCLUDE_LINE = '#include "CKSdlGpuPassthrough.h"\n'

FRIEND_BLOCK = """    // Minecraft frame passthrough: reads the shared memory mailbox and draws
    // the newest foreign frame inside the overlay pass.
    friend CKBOOL CKSdlGpuPassthroughTick(CKSdlGpuRasterizerContext &Context,
                                          CKSdlGpuPassthroughState &State);
"""

MEMBER_ANCHOR = "    CKSdlGpuPresentStage m_Present;\n"
MEMBER_LINE = "    CKSdlGpuPassthroughState m_Passthrough;\n"

FRAME_ANCHOR = """    m_FrameState.Phase = CKFF_FRAME_OVERLAY;
    UpdateTargetExtents();
    return TRUE;
"""
FRAME_PATCH = """    m_FrameState.Phase = CKFF_FRAME_OVERLAY;
    UpdateTargetExtents();
    // Draws the foreign frame over the scene, under the game's 2D interface.
    CKSdlGpuPassthroughTick(*this, m_Passthrough);
    return TRUE;
"""

CMAKE_SOURCE_ANCHOR = "    Rasterizer/CKSdlGpuRasterizerNative.cpp\n"
CMAKE_SOURCE_LINE = "    Rasterizer/CKSdlGpuRasterizerPassthrough.cpp\n"
CMAKE_HEADER_ANCHOR = "    Rasterizer/CKSdlGpuPresentStage.h\n"
CMAKE_HEADER_LINE = "    Rasterizer/CKSdlGpuPassthrough.h\n"


def replace_once(text, anchor, payload, label):
    if payload.strip() and payload in text:
        print(f"  = {label}: already applied")
        return text
    count = text.count(anchor)
    if count != 1:
        sys.exit(f"FAIL {label}: anchor found {count} times, expected 1")
    print(f"  + {label}")
    return text.replace(anchor, payload, 1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--revert", action="store_true", help="remove the copied sources and every edit")
    args = parser.parse_args()

    if not RASTER.is_dir():
        sys.exit(f"FAIL: {RASTER} not found")

    if args.revert:
        for _, dest in COPIES:
            target = RASTER / dest
            if target.exists():
                target.unlink()
                print(f"  - {dest}")
        for path, payload, label in (
            (CONTEXT_H, INCLUDE_LINE, "context include"),
            (CONTEXT_H, FRIEND_BLOCK, "context friend"),
            (CONTEXT_H, MEMBER_LINE, "context member"),
            (CMAKE, CMAKE_SOURCE_LINE, "cmake source"),
            (CMAKE, CMAKE_HEADER_LINE, "cmake header"),
        ):
            text = path.read_text()
            if payload in text:
                path.write_text(text.replace(payload, "", 1))
                print(f"  - {label}")
        text = FRAME_CPP.read_text()
        if FRAME_PATCH in text:
            FRAME_CPP.write_text(text.replace(FRAME_PATCH, FRAME_ANCHOR, 1))
            print("  - frame tick")
        return

    for source, dest in COPIES:
        shutil.copyfile(HERE / source, RASTER / dest)
        print(f"  > {dest}")

    context = CONTEXT_H.read_text()
    context = replace_once(context, INCLUDE_ANCHOR, INCLUDE_ANCHOR + INCLUDE_LINE, "context include")
    context = replace_once(context, MEMBER_ANCHOR, FRIEND_BLOCK + MEMBER_ANCHOR, "context friend")
    context = replace_once(context, MEMBER_ANCHOR, MEMBER_ANCHOR + MEMBER_LINE, "context member")
    CONTEXT_H.write_text(context)

    cmake = CMAKE.read_text()
    cmake = replace_once(cmake, CMAKE_SOURCE_ANCHOR, CMAKE_SOURCE_ANCHOR + CMAKE_SOURCE_LINE, "cmake source")
    cmake = replace_once(cmake, CMAKE_HEADER_ANCHOR, CMAKE_HEADER_ANCHOR + CMAKE_HEADER_LINE, "cmake header")
    CMAKE.write_text(cmake)

    frame = FRAME_CPP.read_text()
    frame = replace_once(frame, FRAME_ANCHOR, FRAME_PATCH, "frame tick")
    FRAME_CPP.write_text(frame)

    print("ok: rebuild with  cmake --build --preset linux-x64-runtime-release --target stage")


if __name__ == "__main__":
    main()
