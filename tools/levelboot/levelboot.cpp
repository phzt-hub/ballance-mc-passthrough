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

    XString resolvedFile = filename;
    if (pm->ResolveFileName(resolvedFile, DATA_PATH_IDX) != CK_OK)
    {
        CLogger::Get().Error("Failed to resolve level file %s", filename);
        return false;
    }

    CKLevel *level = m_CKContext->GetCurrentLevel();
    if (!level)
    {
        CLogger::Get().Error("No current level to merge into!");
        return false;
    }

    CKScene *scene = m_CKContext->GetCurrentScene();

    // Snapshot of what the composition already owns. The PoC hides it so a dumped
    // frame shows the level file alone instead of the menu drawn on top of it.
    XObjectPointerArray preexisting;
    {
        const XObjectPointerArray all = m_CKContext->GetObjectListByType(CKCID_BEOBJECT, TRUE);
        for (CKObject **it = all.Begin(); it != all.End(); ++it)
            preexisting.PushBack(*it);
    }

    CKObjectArray *array = CreateCKObjectArray();
    if (!array)
    {
        CLogger::Get().Error("Failed to create CKObjectArray!");
        return false;
    }

    const int before = m_CKContext->GetObjectCount();

    // A Ballance level .NMO carries no CKLevel of its own: the level object, the
    // managers and the gameplay scripts all live in base.cmo, and the original
    // player merges a level into that composition. So the context is not reset.
    CKERROR res = m_CKContext->Load(resolvedFile.Str(), array,
                                    (CK_LOAD_FLAGS)(CK_LOAD_DEFAULT | CK_LOAD_CHECKDEPENDENCIES));
    if (res != CK_OK)
    {
        CLogger::Get().Error("Failed to load level file: %s (0x%08x)", resolvedFile.CStr(), res);
        DeleteCKObjectArray(array);
        return false;
    }

    int attached = 0;
    int named = 0;
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

    CLogger::Get().Debug("Merged '%s' into level '%s': %d object(s), context %d -> %d object(s), scene %s",
                         resolvedFile.CStr(),
                         level->GetName() ? level->GetName() : "(unnamed)",
                         attached, before, m_CKContext->GetObjectCount(),
                         scene ? "present" : "absent");

    // Re-launching is what registers the merged objects with the render context
    // (CKScene::Init -> CKRenderContext::AddObject) and activates their scripts;
    // adding them to the level alone is not enough to get them drawn.
    level->LaunchScene(NULL);

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
