// PoC script dump: every behavioral object in the context, its scripts, and
// the scripts' input/output pins.
//
// Written for one question: base.cmo already carries the gameplay scripts
// (Gameplay_Ingame Script and friends), so a merged level does not lack a
// script -- it lacks a trigger. This dump says which object owns each script
// and which input pin has to be activated to start it.
//
//   BALLANCE_POC_DUMPSCRIPT=1   dump at LoadLevel() entry and at exit

#include <string.h>

static bool PocScriptWanted(const char *name)
{
    static const char *const kWords[] = {
        "Gameplay", "Menu", "Load", "Level", "Start", "Play", "Ball", "Cam",
        "Intro", "Sector", "Tutorial", "Events", "Refresh", "Pause", "Dead",
        "End", "Highscore", "Unpause", "Exit", "reset", "next", "Default",
        "Build", "Sound", "Ballsound"
    };
    if (!name)
        return false;
    for (size_t i = 0; i < sizeof(kWords) / sizeof(kWords[0]); ++i)
        if (strstr(name, kWords[i]))
            return true;
    return false;
}

void PocDumpScripts(CKContext *context, const char *phase)
{
    if (!context || !getenv("BALLANCE_POC_DUMPSCRIPT"))
        return;

    const char *label = phase ? phase : "?";
    CLogger::Get().Debug("PoC script dump (%s): begin", label);

    const XObjectPointerArray all = context->GetObjectListByType(CKCID_BEOBJECT, TRUE);
    int withScripts = 0;
    int dumped = 0;
    for (CKObject **it = all.Begin(); it != all.End(); ++it)
    {
        CKObject *obj = *it;
        if (!obj)
            continue;
        CKBeObject *beo = (CKBeObject *)obj;
        const int count = beo->GetScriptCount();
        if (count <= 0)
            continue;
        ++withScripts;
        const char *oname = obj->GetName() ? obj->GetName() : "(unnamed)";
        const bool interestingOwner = PocScriptWanted(oname);
        for (int s = 0; s < count; ++s)
        {
            CKBehavior *beh = beo->GetScript(s);
            if (!beh)
                continue;
            const char *bname = beh->GetName() ? beh->GetName() : "(unnamed)";
            if (!interestingOwner && !PocScriptWanted(bname) && dumped >= 250)
                continue;
            CLogger::Get().Debug("PoC script: owner='%s' cid=%d script='%s' cid=%d flags=0x%08x in=%d out=%d params=%d",
                                 oname, obj->GetClassID(), bname, beh->GetClassID(),
                                 (unsigned)beh->GetFlags(), beh->GetInputCount(),
                                 beh->GetOutputCount(), beh->GetInputParameterCount());
            for (int i = 0; i < beh->GetInputCount(); ++i)
            {
                CKBehaviorIO *io = beh->GetInput(i);
                if (!io)
                    continue;
                CLogger::Get().Debug("    in[%d] '%s' active=%d", i,
                                     io->GetName() ? io->GetName() : "(unnamed)", (int)io->IsActive());
            }
            for (int i = 0; i < beh->GetOutputCount(); ++i)
            {
                CKBehaviorIO *io = beh->GetOutput(i);
                if (!io)
                    continue;
                CLogger::Get().Debug("    out[%d] '%s' active=%d", i,
                                     io->GetName() ? io->GetName() : "(unnamed)", (int)io->IsActive());
            }
            for (int i = 0; i < beh->GetInputParameterCount(); ++i)
            {
                CKObject *p = beh->GetInputParameterObject(i);
                if (!p)
                    continue;
                CLogger::Get().Debug("    param[%d] '%s' cid=%d", i,
                                     p->GetName() ? p->GetName() : "(unnamed)", p->GetClassID());
            }
            ++dumped;
        }
    }
    CLogger::Get().Debug("PoC script dump (%s): %d object(s) with scripts, %d script(s) dumped",
                         label, withScripts, dumped);
}

// Frame-gated variant: base.cmo alone never calls LoadLevel(), so the menu
// composition has to be sampled while it runs. Dumps once at frame
// BALLANCE_POC_DUMPSCRIPT_FRAME (default 90, when the menu has finished
// loading its NMOs).
void PocDumpScriptsAuto(CKContext *context)
{
    if (!context || !getenv("BALLANCE_POC_DUMPSCRIPT"))
        return;

    static int frame = 0;
    ++frame;
    const char *spec = getenv("BALLANCE_POC_DUMPSCRIPT_FRAME");
    const int target = spec ? atoi(spec) : 90;
    if (frame != target)
        return;

    PocDumpScripts(context, "auto");
}
