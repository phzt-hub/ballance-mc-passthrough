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
  * BALLANCE_POC_NMO=a,b,...  -- extra NMOs merged alongside the level
  * BALLANCE_POC_START_LEVEL=chain|all -- fire the level start-up scripts
                                 (they are inactive after a plain merge, which
                                 is why a merged level never starts)
  * BALLANCE_POC_START_FRAME=n -- render call that fires the deferred pass (120)
  * BALLANCE_POC_STOP_MENU=1  -- stop the menu scripts, which otherwise keep
                                 re-attaching Cam_MenuLevel every frame

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
            (HERE / "pocscripts.cpp").read_text(encoding="utf-8") + "\n" +
            (HERE / "poc2d.cpp").read_text(encoding="utf-8") + "\n" +
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
    PocLevelStartAuto(m_CKContext);
    PocLevelStopMenu(m_CKContext);
    Poc1FrameLog(m_CKContext, m_RenderContext);
    Poc1ForceCursor(m_CKContext);
    Poc1InputLog(m_CKContext, m_RenderContext);
    PocDumpScriptsAuto(m_CKContext);
    PocDump2DAuto(m_CKContext);
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


# --- SdlInputManager: keep the OS pointer visible --------------------------
# The "Mouse Cursor" building block hides the OS pointer and draws its own
# sprite from a post-sprite callback. That sprite never reaches the screen in
# this SDL/GL port, and the push-button block gates clicks on
# GetCursorVisibility(), so the menu becomes unclickable. Until the sprite is
# ported, ShowCursor() keeps the OS pointer on (BALLANCE_SPRITE_CURSOR=1 opts
# out). Lives in the SdlInputManager submodule, hence the same script-or-lose
# treatment as the Player patch.
CURSOR_CPP = ROOT / "Source/Managers/SdlInputManager/Cursor.cpp"
CURSOR_OLD = '''#include "SdlInputManager.h"

#include "VxWindowFunctions.h"

void SdlInputManager::ShowCursor(CKBOOL iShow)
{
    m_ShowCursor = iShow;
    EnsureCursorVisible(iShow);
}'''
CURSOR_NEW = (HERE / "sdl-cursor.cpp").read_text(encoding="utf-8").rstrip()

cursor_text = CURSOR_CPP.read_text(encoding="utf-8")
if "KeepSystemCursorVisible" in cursor_text:
    print(CURSOR_CPP.name + " (cursor): already")
elif cursor_text.count(CURSOR_OLD) != 1:
    print(CURSOR_CPP.name + " (cursor): SKIPPED - unexpected content, patch by hand")
else:
    CURSOR_CPP.write_text(cursor_text.replace(CURSOR_OLD, CURSOR_NEW), encoding="utf-8")
    print(CURSOR_CPP.name + " (cursor): patched")
