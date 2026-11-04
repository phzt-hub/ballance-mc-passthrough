// BALLANCE_POC_START_LEVEL -- fire the level start-up scripts by hand.
//
// Merging a level's geometry only adds meshes to the scene: the scripts that
// actually start a level sit on objects base.cmo already owns, and their Start
// input is inactive. CKContext::Play() only calls CKLevel::ActivateAllScript()
// when the context was reset, and an initial load is not a reset -- so nothing
// ever fires them, and the menu camera keeps flying over the merged geometry.
//
// The chain mode fires only the level start-up scripts, leaving the composition
// loaders (Loading_Manager, Object Load, Delete_*) alone: they would try to load
// the level NMOs a second time.
static bool PocLevelStartWanted(const char *name, const char *owner)
{
    static const char *const chain[] = {
        "levelinit", "level_init", "gameplay_init", "balls_init", "init ballpieces",
        "animtrafo_init", "menulevel_init", "default level", "gameplay_refresh",
    };

    for (size_t i = 0; i < sizeof(chain) / sizeof(chain[0]); ++i)
    {
        const char *patterns[2] = { name, owner };
        for (int p = 0; p < 2; ++p)
        {
            if (!patterns[p])
                continue;
            const size_t len = strlen(patterns[p]);
            const size_t plen = strlen(chain[i]);
            for (size_t s = 0; s + plen <= len; ++s)
            {
                size_t k = 0;
                while (k < plen &&
                       tolower((unsigned char)patterns[p][s + k]) == (unsigned char)chain[i][k])
                    ++k;
                if (k == plen)
                    return true;
            }
        }
    }
    return false;
}

static void PocLevelStartScripts(CKContext *ctx, CKLevel *level, const char *spec)
{
    if (!ctx || !level || !spec || !*spec || strcmp(spec, "0") == 0)
        return;

    const bool all = (strcmp(spec, "all") == 0);
    CKScene *scene = ctx->GetCurrentScene();
    CKBehaviorManager *bm = ctx->GetBehaviorManager();

    int seen = 0, fired = 0;
    const XObjectPointerArray beos = ctx->GetObjectListByType(CKCID_BEOBJECT, TRUE);
    for (CKObject **it = beos.Begin(); it != beos.End(); ++it)
    {
        CKBeObject *beo = (CKBeObject *)*it;
        if (!beo)
            continue;

        const int scripts = beo->GetScriptCount();
        for (int i = 0; i < scripts; ++i)
        {
            CKBehavior *script = beo->GetScript(i);
            if (!script || script->GetType() != CKBEHAVIORTYPE_SCRIPT)
                continue;

            const char *name = script->GetName();
            const char *owner = beo->GetName();
            ++seen;

            const bool wanted = all || PocLevelStartWanted(name, owner);
            CLogger::Get().Debug("PoC start-level: %s script '%s' on '%s'",
                                 wanted ? "activate" : "skip",
                                 name ? name : "(unnamed)", owner ? owner : "(unnamed)");
            if (!wanted)
                continue;

            // Activate(TRUE, TRUE) == Reset() then the active flag; Reset() clears
            // every IO and, for a script, re-arms the Start input (in[0]).
            script->Activate(TRUE, TRUE);
            if (bm)
                bm->AddObject(beo);
            if (scene && beo->IsInScene(scene))
                scene->SetObjectFlags((CKSceneObject *)beo,
                                      (CK_SCENEOBJECT_FLAGS)CK_SCENEOBJECT_ACTIVE);
            ++fired;
        }
    }

    CLogger::Get().Debug("PoC start-level '%s': %d of %d script(s) fired", spec, fired, seen);
}


// The menu owns the camera: its scripts re-attach Cam_MenuLevel every frame, so a
// level that starts underneath them is still framed by the menu path. Switching
// them off is what lets the gameplay camera show up. BALLANCE_POC_STOP_MENU=1.
static void PocLevelStopMenu(CKContext *ctx);

// Deferred pass. At LoadLevel() time the menu has only just started: the groups
// that own the gameplay scripts (All_Gameplay, All_Balls, All_Levelinit, ...) are
// pulled in by Loading_Manager over the next second, so a chain fired right after
// the merge sees 9 scripts at most. Fire the chain once more when the menu has
// settled -- BALLANCE_POC_START_FRAME (default 120) render calls after start.
static void PocLevelStartAuto(CKContext *ctx)
{
    static int calls = 0;
    static bool done = false;

    ++calls;
    if (done || !ctx)
        return;

    const char *spec = getenv("BALLANCE_POC_START_LEVEL");
    if (!spec || !*spec || strcmp(spec, "0") == 0)
        return;

    const char *frameSpec = getenv("BALLANCE_POC_START_FRAME");
    const int startFrame = (frameSpec && *frameSpec) ? atoi(frameSpec) : 120;
    if (calls < startFrame)
        return;

    CKLevel *level = ctx->GetCurrentLevel();
    if (!level)
        return;

    CLogger::Get().Debug("PoC start-level: deferred pass at render call %d", calls);
    PocLevelStartScripts(ctx, level, spec);
    PocLevelStopMenu(ctx);
    done = true;
}

static bool PocNameHas(const char *name, const char *needle)
{
    if (!name || !needle || !*needle)
        return false;
    const size_t len = strlen(name);
    const size_t nlen = strlen(needle);
    for (size_t s = 0; s + nlen <= len; ++s)
    {
        size_t k = 0;
        while (k < nlen && tolower((unsigned char)name[s + k]) == tolower((unsigned char)needle[k]))
            ++k;
        if (k == nlen)
            return true;
    }
    return false;
}

static void PocLevelStopMenu(CKContext *ctx)
{
    // Called every frame: latch only once something was actually stopped, so the
    // very first frames (nothing loaded yet) do not consume the single attempt.
    static bool latched = false;
    if (latched || !ctx || !getenv("BALLANCE_POC_STOP_MENU"))
        return;

    CKBehaviorManager *bm = ctx->GetBehaviorManager();
    int stopped = 0;
    const XObjectPointerArray beos = ctx->GetObjectListByType(CKCID_BEOBJECT, TRUE);
    for (CKObject **it = beos.Begin(); it != beos.End(); ++it)
    {
        CKBeObject *beo = (CKBeObject *)*it;
        if (!beo)
            continue;

        const char *owner = beo->GetName();
        const bool menuOwner = PocNameHas(owner, "menu");
        const int scripts = beo->GetScriptCount();

        for (int i = 0; i < scripts; ++i)
        {
            CKBehavior *script = beo->GetScript(i);
            if (!script || script->GetType() != CKBEHAVIORTYPE_SCRIPT)
                continue;
            if (!menuOwner && !PocNameHas(script->GetName(), "menu"))
                continue;
            script->Activate(FALSE, FALSE);
            ++stopped;
            CLogger::Get().Debug("PoC stop-menu: stopped script '%s' on '%s'",
                                 script->GetName() ? script->GetName() : "(unnamed)",
                                 owner ? owner : "(unnamed)");
            if (bm)
                bm->RemoveObjectNextFrame(beo);
        }
    }

    if (stopped > 0)
    {
        latched = true;
        CLogger::Get().Debug("PoC stop-menu: %d menu script(s) stopped", stopped);
    }
}

bool CGamePlayer::LoadLevel(const char *filename)
{
    if (!filename || (*filename) == '\0')
        return false;

    if (!m_CKContext)
    {
        CLogger::Get().Error("CKContext is not initialized!");
        return false;
    }

    CKPathManager *pm = m_CKContext->GetPathManager();
    if (!pm)
    {
        CLogger::Get().Error("Failed to get CKPathManager!");
        return false;
    }

    CKLevel *level = m_CKContext->GetCurrentLevel();
    if (!level)
    {
        CLogger::Get().Error("No current level to merge into!");
        return false;
    }

    CKScene *scene = m_CKContext->GetCurrentScene();

    PocDumpScripts(m_CKContext, "entry");

    // Snapshot of what the composition already owns. The PoC hides it so a dumped
    // frame shows the level file alone instead of the menu drawn on top of it.
    XObjectPointerArray preexisting;
    {
        const XObjectPointerArray all = m_CKContext->GetObjectListByType(CKCID_BEOBJECT, TRUE);
        for (CKObject **it = all.Begin(); it != all.End(); ++it)
            preexisting.PushBack(*it);
    }

    const int before = m_CKContext->GetObjectCount();

    // Files to merge. A level .NMO carries only geometry: the level object, the
    // managers and the gameplay scripts live in base.cmo, and the gameplay logic
    // itself lives in Gameplay.nmo -- so merging the level geometry alone never
    // starts a level. BALLANCE_POC_NMO names the support NMOs (Levelinit / Gameplay
    // / Camera / Intro) to merge alongside the level, comma separated.
    char extraSpec[4096];
    extraSpec[0] = 0;
    const char *envExtra = getenv("BALLANCE_POC_NMO");
    if (envExtra && *envExtra)
    {
        strncpy(extraSpec, envExtra, sizeof(extraSpec) - 1);
        extraSpec[sizeof(extraSpec) - 1] = 0;
    }

    char fileNames[16][2048];
    int fileCount = 0;
    fileNames[fileCount][0] = 0;
    strncpy(fileNames[fileCount], filename, sizeof(fileNames[0]) - 1);
    fileNames[fileCount][sizeof(fileNames[0]) - 1] = 0;
    ++fileCount;

    char *segment = extraSpec;
    while (*segment && fileCount < 16)
    {
        char *comma = strchr(segment, ',');
        if (comma) *comma = 0;
        size_t len = strlen(segment);
        while (len > 0 && (segment[len - 1] == ' ' || segment[len - 1] == '\t' ||
                           segment[len - 1] == '\r' || segment[len - 1] == '\n'))
            segment[--len] = 0;
        size_t start = 0;
        while (segment[start] == ' ' || segment[start] == '\t') ++start;
        if (segment[start])
        {
            strncpy(fileNames[fileCount], segment + start, sizeof(fileNames[0]) - 1);
            fileNames[fileCount][sizeof(fileNames[0]) - 1] = 0;
            ++fileCount;
        }
        if (!comma) break;
        segment = comma + 1;
    }

    int attached = 0;
    int named = 0;
    int mergedFiles = 0;

    for (int f = 0; f < fileCount; ++f)
    {
        XString resolvedFile = fileNames[f];
        if (pm->ResolveFileName(resolvedFile, DATA_PATH_IDX) != CK_OK)
        {
            if (f == 0)
            {
                CLogger::Get().Error("Failed to resolve level file %s", fileNames[f]);
                return false;
            }
            CLogger::Get().Warn("PoC could not resolve NMO '%s' (skipped)", fileNames[f]);
            continue;
        }

        CKObjectArray *array = CreateCKObjectArray();
        if (!array)
        {
            CLogger::Get().Error("Failed to create CKObjectArray!");
            return false;
        }

        // A Ballance level .NMO carries no CKLevel of its own: the level object, the
        // managers and the gameplay scripts all live in base.cmo, and the original
        // player merges a level into that composition. So the context is not reset.
        CKERROR res = m_CKContext->Load(resolvedFile.Str(), array,
                                        (CK_LOAD_FLAGS)(CK_LOAD_DEFAULT | CK_LOAD_CHECKDEPENDENCIES));
        if (res != CK_OK)
        {
            CLogger::Get().Error("Failed to load NMO '%s': 0x%08x (skipped)", resolvedFile.CStr(), res);
            DeleteCKObjectArray(array);
            if (f == 0)
                return false;
            continue;
        }

        int fileAttached = 0;
        for (array->Reset(); !array->EndOfList(); array->Next())
        {
            CKObject *obj = array->GetData(m_CKContext);
            if (!obj)
                continue;

            if (!CKIsChildClassOf(obj, CKCID_BEOBJECT))
                continue;

            level->AddObject(obj);
            if (scene && CKIsChildClassOf(obj, CKCID_SCENEOBJECT))
                scene->AddObject((CKSceneObject *)obj);
            // CKScene::Init() registers the scene's render objects with the render
            // contexts only when a scene is launched, and the scene we merged into was
            // launched long before these objects existed -- so register them by hand.
            if (CKIsChildClassOf(obj, CKCID_RENDEROBJECT))
                m_RenderContext->AddObjectWithHierarchy((CKRenderObject *)obj);
            Poc1NoteMerged(obj);
            ++attached;
            ++fileAttached;

            if (named < 8 && obj->GetName())
            {
                const char *vis = "?";
                if (CKIsChildClassOf(obj, CKCID_3DENTITY))
                    vis = ((CK3dEntity *)obj)->IsVisible() ? "visible" : "hidden";
                else if (CKIsChildClassOf(obj, CKCID_2DENTITY))
                    vis = ((CK2dEntity *)obj)->IsVisible() ? "visible" : "hidden";
                CLogger::Get().Debug("  level object: %s (cid %d, %s, attached=%d)", obj->GetName(), obj->GetClassID(), vis,
                                     CKIsChildClassOf(obj, CKCID_RENDEROBJECT) ? (int)m_RenderContext->IsObjectAttached((CKRenderObject *)obj) : -1);
                ++named;
            }
        }
        DeleteCKObjectArray(array);
        ++mergedFiles;

        CLogger::Get().Debug("Merged '%s': %d object(s)", resolvedFile.CStr(), fileAttached);
    }

    CLogger::Get().Debug("Merged %d NMO file(s) into level '%s': %d object(s), context %d -> %d object(s), scene %s",
                         mergedFiles,
                         level->GetName() ? level->GetName() : "(unnamed)",
                         attached, before, m_CKContext->GetObjectCount(),
                         scene ? "present" : "absent");

    // Re-launching is what registers the merged objects with the render context
    // (CKScene::Init -> CKRenderContext::AddObject) and activates their scripts;
    // adding them to the level alone is not enough to get them drawn.
    level->LaunchScene(NULL);

    // LaunchScene() calls CKBehaviorManager::RemoveAllObjects(), so the scripts
    // have to be fired after it, never before.
    PocLevelStartScripts(m_CKContext, level, getenv("BALLANCE_POC_START_LEVEL"));

    PocDumpScripts(m_CKContext, "exit");

    const CKBOOL isolate = getenv("BALLANCE_POC_ISOLATE") != NULL;
    if (isolate)
    {
        int hidden = 0;
        for (CKObject **it = preexisting.Begin(); it != preexisting.End(); ++it)
        {
            CKObject *obj = *it;
            if (!obj || obj == level)
                continue;
            if (CKIsChildClassOf(obj, CKCID_3DENTITY))
                ((CK3dEntity *)obj)->Show(CKHIDE);
            else if (CKIsChildClassOf(obj, CKCID_2DENTITY))
                ((CK2dEntity *)obj)->Show(CKHIDE);
            ++hidden;
        }
        CLogger::Get().Debug("PoC isolate: hid %d composition object(s); the level scripts may still drive the eye", hidden);
    }

    const char *camSpec = getenv("BALLANCE_POC_CAM");
    if (camSpec)
    {
        float px = 0, py = 0, pz = 0, tx = 0, ty = 0, tz = 0;
        if (sscanf(camSpec, "%f,%f,%f,%f,%f,%f", &px, &py, &pz, &tx, &ty, &tz) == 6)
        {
            // The Ballance compositions carry no CKCamera object (their scripts set
            // the eye through the render context), so create one when there is none.
            const XObjectPointerArray cams = m_CKContext->GetObjectListByType(CKCID_CAMERA, TRUE);
            const bool created = cams.Size() == 0;
            CKCamera *cam = created ? (CKCamera *)m_CKContext->CreateObject(CKCID_CAMERA, "PoCCamera")
                                    : (CKCamera *)cams[0];
            if (cam)
            {
                if (created)
                    level->AddObject(cam);

                cam->SetPosition3f(px, py, pz);
                VxVector target(tx, ty, tz);
                cam->LookAt(&target);
                cam->SetFov(1.1f);
                cam->SetFrontPlane(1.0f);
                cam->SetBackPlane(20000.0f);
                m_RenderContext->AttachViewpointToCamera(cam);

                m_RenderContext->SetViewTransformationMatrix(cam->GetInverseWorldMatrix());
                VxMatrix projection;
                const float aspect = (m_RenderContext->GetHeight() > 0)
                                         ? (float)m_RenderContext->GetWidth() / (float)m_RenderContext->GetHeight()
                                         : 4.0f / 3.0f;
                projection.Perspective(1.1f, aspect, 1.0f, 20000.0f);
                m_RenderContext->SetProjectionTransformationMatrix(projection);

                const VxMatrix &view = cam->GetInverseWorldMatrix();
                for (int row = 0; row < 4; ++row)
                    CLogger::Get().Debug("PoC view row %d: %g %g %g %g", row,
                                         view[row][0], view[row][1], view[row][2], view[row][3]);

                CLogger::Get().Debug("PoC camera%s: pos(%.1f %.1f %.1f) -> target(%.1f %.1f %.1f)",
                                     created ? " (created)" : "", px, py, pz, tx, ty, tz);
            }
            else
            {
                CLogger::Get().Warn("PoC camera requested but no camera could be created");
            }
        }
    }

    m_RenderContext->Render();

    return true;
}
