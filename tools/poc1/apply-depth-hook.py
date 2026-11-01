#!/usr/bin/env python3
"""Apply the PoC-1 depth dump hook into Source/RenderEngine.

The hook lives in a git submodule, so it is shipped as a script instead of a
commit: run this after every submodule update. It is idempotent.
"""
import os
import shutil
from pathlib import Path

ROOT = Path(os.environ.get("BALLANCE_ROOT", "/home/phzt/src/Ballanced")).resolve()
RASTER = ROOT / "Source/RenderEngine/src/CKRasterizer/CKSdlGpuRasterizer"
IMPL = Path(__file__).resolve().parent / "depth-dump.cpp"
DEST = RASTER / "Rasterizer/CKSdlGpuRasterizerDepthDump.cpp"
CMAKE = RASTER / "CMakeLists.txt"
HEADER = RASTER / "Rasterizer/CKSdlGpuRasterizerContext.h"
COMMANDS = RASTER / "Native/CKSdlGpuCommands.cpp"

CMAKE_ANCHOR = "    Rasterizer/CKSdlGpuRasterizerContext.cpp\n"
CMAKE_LINE = "    Rasterizer/CKSdlGpuRasterizerDepthDump.cpp\n"
HEADER_ANCHOR = "    CKERROR Submit(CKPresentSync Sync, CKBOOL PresentWindow, CKDWORD *SubmissionNumber);\n"
HEADER_LINE = (
    "    // PoC-1 scaffold (ballance-mc-passthrough): dump the depth buffer of a\n"
    "    // submitted frame when BALLANCE_POC1_DEPTH_FRAMES is set.\n"
    "    void Poc1MaybeDumpDepth();\n"
)
COMMANDS_ANCHOR = "    CollectJobs(kFrameJobBudgetNs);\n"
COMMANDS_LINE = "    Poc1MaybeDumpDepth();\n"


def replace_once(path, anchor, addition, before=True):
    text = path.read_text(encoding="utf-8")
    if addition in text:
        return "already"
    found = text.count(anchor)
    if found != 1:
        raise SystemExit(str(path) + ": anchor found " + str(found) + " times, refusing")
    text = text.replace(anchor, (addition + anchor) if before else (anchor + addition))
    path.write_text(text, encoding="utf-8")
    return "patched"


def main():
    for path in (CMAKE, HEADER, COMMANDS, DEST):
        if not path.parent.is_dir():
            raise SystemExit("missing " + str(path.parent) + "; is the submodule initialized?")
    shutil.copyfile(IMPL, DEST)
    print("impl    " + str(DEST.relative_to(ROOT)))
    print("cmake   " + replace_once(CMAKE, CMAKE_ANCHOR, CMAKE_LINE, before=False))
    print("header  " + replace_once(HEADER, HEADER_ANCHOR, HEADER_LINE, before=False))
    print("submit  " + replace_once(COMMANDS, COMMANDS_ANCHOR, COMMANDS_LINE, before=False))


if __name__ == "__main__":
    main()
