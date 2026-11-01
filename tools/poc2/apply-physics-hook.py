#!/usr/bin/env python3
"""Apply the PoC-2 obstacle hook into Source/BuildingBlocks/physics_RT.

physics_RT lives in the Ballanced checkout (not a submodule), but it is still not
ours: the patch is shipped as a script and is idempotent.
"""
import os
import shutil
from pathlib import Path

ROOT = Path(os.environ.get("BALLANCE_ROOT", "/home/phzt/src/Ballanced")).resolve()
HERE = Path(__file__).resolve().parent
DEST = ROOT / "Source/BuildingBlocks/physics_RT"
CMAKE = DEST / "CMakeLists.txt"
IMPL = DEST / "Poc2Obstacles.cpp"
HEADER = DEST / "Poc2Obstacles.h"
MANAGER = DEST / "CKIpionManager.cpp"

CMAKE_ANCHOR = "                PhysicsContact.h\n"
CMAKE_LINE = "                Poc2Obstacles.cpp\n                Poc2Obstacles.h\n"

INCLUDE_ANCHOR = '#include "CKIpionManager.h"\n'
INCLUDE_LINE = '#include "Poc2Obstacles.h"\n'

SIMULATE_ANCHOR = """    Simulate(m_TimeManager->GetLastDeltaTime());

    return CK_OK;
"""
SIMULATE_PATCH = """    const double poc2Start = Poc2NowMs();
    Poc2BeforeFrame(this);
    Simulate(m_TimeManager->GetLastDeltaTime());
    Poc2AfterFrame(this, Poc2NowMs() - poc2Start);

    return CK_OK;
"""


def replace_once(path, anchor, addition, count_expected=1):
    text = path.read_text(encoding="utf-8")
    if addition in text:
        return "already"
    found = text.count(anchor)
    if found != count_expected:
        raise SystemExit(f"{path}: anchor found {found} times (expected {count_expected}), refusing")
    path.write_text(text.replace(anchor, addition, 1), encoding="utf-8")
    return "patched"


def main():
    if not DEST.is_dir():
        raise SystemExit(f"missing {DEST}; is the Ballanced checkout present?")
    shutil.copyfile(HERE / "poc2-obstacles.cpp", IMPL)
    shutil.copyfile(HERE / "Poc2Obstacles.h", HEADER)
    print("impl    " + str(IMPL.relative_to(ROOT)))
    print("cmake   " + replace_once(CMAKE, CMAKE_ANCHOR, CMAKE_ANCHOR + CMAKE_LINE))
    print("include " + replace_once(MANAGER, INCLUDE_ANCHOR, INCLUDE_ANCHOR + INCLUDE_LINE))
    text = MANAGER.read_text(encoding="utf-8")
    if SIMULATE_PATCH in text:
        print("simulate already")
    else:
        if text.count(SIMULATE_ANCHOR) != 1:
            raise SystemExit(f"{MANAGER}: PostProcess body anchor not unique, refusing")
        MANAGER.write_text(text.replace(SIMULATE_ANCHOR, SIMULATE_PATCH, 1), encoding="utf-8")
        print("simulate patched")


if __name__ == "__main__":
    main()
