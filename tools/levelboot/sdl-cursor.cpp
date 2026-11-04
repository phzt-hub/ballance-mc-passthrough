#include "SdlInputManager.h"

#include <cstdlib>

#include "VxWindowFunctions.h"

// Port fix: a Virtools game that uses the "Mouse Cursor" building block hides
// the OS pointer and paints its own sprite from a post-sprite render callback
// (BuildingBlocks/Interface/Behaviors/MouseCursor.cpp). That sprite path does
// not land on screen in this SDL/GL port, so a hidden pointer is simply an
// invisible pointer -- and the push-button building block refuses to arm while
// GetCursorVisibility() is FALSE (it tests the visibility before letting a
// click through). The whole menu then becomes unclickable.
// Until the sprite cursor is ported, keep the OS pointer on and report it as
// visible. BALLANCE_SPRITE_CURSOR=1 restores the original behaviour.
static CKBOOL KeepSystemCursorVisible()
{
    static const CKBOOL keep = [] {
        const char *env = std::getenv("BALLANCE_SPRITE_CURSOR");
        return CKBOOL(!(env && *env != '\0' && *env != '0'));
    }();
    return keep;
}

void SdlInputManager::ShowCursor(CKBOOL iShow)
{
    if (KeepSystemCursorVisible())
        iShow = TRUE;
    m_ShowCursor = iShow;
    EnsureCursorVisible(iShow);
}