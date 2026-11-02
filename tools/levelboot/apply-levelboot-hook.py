#!/usr/bin/env python3
"""Apply the "level boot" hook into Source/Player (Ballanced).

Why this is a patch and not a config change: a Ballance level file
(3D Entities/Level/Level_XX.NMO) carries no CKLevel of its own -- the level
object, the managers and the gameplay scripts all live in base.cmo, and the
original Player.exe merges a level into that composition. Ballanced's
CGamePlayer::Load() resets the context instead, so loading a level through it
always ends in "Failed to retrieve the level!".

The hook adds:
  * CGamePlayer::LoadLevel()  -- merge a level .NMO into the loaded composition
  * --level <file>            -- command line switch that runs it after base.cmo
  * BALLANCE_POC_ISOLATE=1    -- hide everything base.cmo brought, so a dumped
                                 frame shows the level and not the menu
  * BALLANCE_POC_CAM=x,y,z,tx,ty,tz -- park the eye at a fixed spot and aim it
                                 (also pins the view/projection matrices)

The hook lives in git submodules (Source/Player), so it ships as a script:
run this after every submodule update. It is idempotent.
"""
import os
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = Path(os.environ.get("BALLANCE_ROOT", "/home/phzt/src/Ballanced")).resolve()
PLAYER = ROOT / "Source/Player/src"

CONFIG_H = PLAYER / "GameConfig.h"
PLAYER_H = PLAYER / "GamePlayer.h"
PLAYER_CPP = PLAYER / "GamePlayer.cpp"
MAIN_CPP = PLAYER / "Player.cpp"

CONFIG_ANCHOR = '  X_BOOL ("Game",     "Rookie",'
CONFIG_LINE = ('  X_STRING("Game",    "Level",                  levelPath,               "",'
               '                 "--level",                               \'\\0\') \\\n')

H_ANCHOR = "    bool Load(const char *filename = NULL);\n"
H_LINE = "    bool LoadLevel(const char *filename);\n"

CPP_ANCHOR = "void CGamePlayer::Run()\n"
# pocframelog.cpp is concatenated first: LoadLevel() calls Poc1NoteMerged(), and
# Poc1FrameLog() is called from Render(), so both have to be declared before use.
CPP_LINE = ((HERE / "pocframelog.cpp").read_text(encoding="utf-8") + "\n" +
            (HERE / "levelboot.cpp").read_text(encoding="utf-8"))

# Render() is where the answer to "is the merged level on screen?" is
# observable, so the per-frame log call goes there (BALLANCE_POC_FRAMELOG=<n>).
RENDER_ANCHOR = """void CGamePlayer::Render()
{
    m_RenderContext->Render();
}
"""
RENDER_LINE = """void CGamePlayer::Render()
{
    Poc1ApplyCameraPin(m_CKContext, m_RenderContext);
    Poc1FrameLog(m_CKContext, m_RenderContext);
    m_RenderContext->Render();
}
"""

MAIN_ANCHOR = "    player.Play();\n"
MAIN_LINE = """    if (!runtimeConfig.levelPath.IsEmpty())
    {
        CLogger::Get().Debug("Merging level: %s", runtimeConfig.levelPath.CStr());
        if (!player.LoadLevel(runtimeConfig.levelPath.CStr()))
        {
            CLogger::Get().Error("Failed to merge level!");
            ShowErrorMessage("Failed to merge level!");
            player.Shutdown();
            return -1;
        }
    }

"""


def patch(path, anchor, addition, before=True):
    text = path.read_text(encoding="utf-8")
    if addition in text:
        return "already"
    found = text.count(anchor)
    if found != 1:
        raise SystemExit(str(path) + ": anchor found " + str(found) + " times, refusing")
    text = text.replace(anchor, (addition + anchor) if before else (anchor + addition))
    path.write_text(text, encoding="utf-8")
    return "patched"


for path, anchor, addition in (
    (CONFIG_H, CONFIG_ANCHOR, CONFIG_LINE),
    (PLAYER_H, H_ANCHOR, H_LINE),
    (PLAYER_CPP, CPP_ANCHOR, CPP_LINE),
    (MAIN_CPP, MAIN_ANCHOR, MAIN_LINE),
):
    print(path.name + ": " + patch(path, anchor, addition))

# Render(): swap the body, not prepend to it (patch() only inserts whole blocks).
render_text = PLAYER_CPP.read_text(encoding="utf-8")
if RENDER_LINE in render_text:
    print(PLAYER_CPP.name + " (render): already")
else:
    if render_text.count(RENDER_ANCHOR) != 1:
        raise SystemExit("Render() anchor not found exactly once, refusing")
    PLAYER_CPP.write_text(render_text.replace(RENDER_ANCHOR, RENDER_LINE), encoding="utf-8")
    print(PLAYER_CPP.name + " (render): patched")
