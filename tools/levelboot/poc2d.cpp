// Dump the 2D layer (menu buttons, labels) so a headless run can find where the
// "Start" button is and click it with xdotool.
//
// Why this matters: the Ballance menu is a 3D scene with a rotating camera and a
// 2D interface on top. Nothing in the .cmo starts a level by itself -- a click on
// the menu does. Without the click the composition just spins forever, which is
// exactly what every --level run so far showed.
//
//   BALLANCE_POC_DUMP2D=1            dump once
//   BALLANCE_POC_DUMP2D_FRAME=n      at render call n (default 200)
//   BALLANCE_POC_DUMP2D_ALL=1        include hidden entities (default: only visible)

static void PocDump2DAuto(CKContext *ctx)
{
    static int calls = 0;

    ++calls;
    if (!ctx || !getenv("BALLANCE_POC_DUMP2D"))
        return;

    // Frame list, so one run can show the menu before and after a click:
    // BALLANCE_POC_DUMP2D_FRAME=200,600,900
    const char *frameSpec = getenv("BALLANCE_POC_DUMP2D_FRAME");
    bool wanted = false;
    if (!frameSpec || !*frameSpec)
    {
        wanted = (calls >= 200);
    }
    else
    {
        const char *p = frameSpec;
        while (*p)
        {
            while (*p == ',' || *p == ' ')
                ++p;
            if (!*p)
                break;
            char *end = NULL;
            const long v = strtol(p, &end, 10);
            if (end == p)
                break;
            if (v == calls)
                wanted = true;
            p = end;
            if (calls > v && *p != ',')
                break;
        }
    }
    if (!wanted)
        return;

    const bool dumpAll = getenv("BALLANCE_POC_DUMP2D_ALL") != NULL;

    const XObjectPointerArray list = ctx->GetObjectListByType(CKCID_2DENTITY, TRUE);
    int shown = 0;
    CLogger::Get().Debug("PoC 2d dump at render call %d: %d 2d object(s)%s",
                         calls, list.Size(), dumpAll ? " (including hidden)" : "");
    for (CKObject **it = list.Begin(); it != list.End(); ++it)
    {
        CK2dEntity *ent = (CK2dEntity *)*it;
        if (!ent)
            continue;
        if (!dumpAll && !ent->IsVisible())
            continue;

        Vx2DVector pos, size, hpos, hsize;
        ent->GetPosition(pos, FALSE, NULL);
        ent->GetSize(size, FALSE);
        ent->GetPosition(hpos, TRUE, NULL);
        ent->GetSize(hsize, TRUE);

        const char *text = "";
        if (ent->GetClassID() == CKCID_SPRITETEXT)
        {
            CKSTRING t = ((CKSpriteText *)ent)->GetText();
            text = (t && *t) ? t : "";
        }

        CLogger::Get().Debug("PoC 2d: id=%d cid=%d name='%s' text='%s' vis=%d z=%d pos=(%.1f %.1f) size=(%.1f %.1f) hom=(%.4f %.4f)%s%.4f %.4f)",
                             ent->GetID(), (int)ent->GetClassID(),
                             ent->GetName() ? ent->GetName() : "(unnamed)", text,
                             ent->IsVisible() ? 1 : 0, ent->GetZOrder(),
                             pos.x, pos.y, size.x, size.y, hpos.x, hpos.y, "/(", hsize.x, hsize.y);
        ++shown;
    }
    CLogger::Get().Debug("PoC 2d dump: %d object(s) listed", shown);
}
