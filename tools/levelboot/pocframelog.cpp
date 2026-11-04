// PoC frame log: what the render context is actually looking at, per frame.
//
// Written for one question and nothing else: the level merged into base.cmo is
// attached to the render context (IsObjectAttached says so) and yet a dumped
// frame looks exactly like the composition's. So: which scene is current, which
// camera is attached, where does the eye sit, and how many merged entities land
// inside the view frustum? Public engine API only, on purpose -- no internal
// headers, no build flags.
//
//   BALLANCE_POC_FRAMELOG=<n>   log frame 1..3 and then every n-th frame (n<2 -> 60)
//
// Build note: this file is concatenated *before* levelboot.cpp, because
// LoadLevel() calls Poc1NoteMerged() for every object it merged in.

#include <stdio.h>
#include <stdlib.h>

static XArray<CK_ID> s_PocMergedIds;

static void Poc1NoteMerged(CKObject *object)
{
    if (object)
        s_PocMergedIds.PushBack(object->GetID());
}

static bool Poc1IsMerged(CK_ID id)
{
    for (CK_ID *it = s_PocMergedIds.Begin(); it != s_PocMergedIds.End(); ++it)
        if (*it == id)
            return true;
    return false;
}

// Virtools multiplies row vectors: p_clip = p_world * View * Projection.
static void Poc1Transform(const VxMatrix &m, const float in[4], float out[4])
{
    for (int col = 0; col < 4; ++col)
        out[col] = in[0] * m[0][col] + in[1] * m[1][col] + in[2] * m[2][col] + in[3] * m[3][col];
}

static bool Poc1InsideFrustum(const VxMatrix &view, const VxMatrix &projection, const VxVector &world,
                              float ndc[3])
{
    const float p[4] = {world.x, world.y, world.z, 1.0f};
    float viewPos[4];
    float clip[4];
    Poc1Transform(view, p, viewPos);
    Poc1Transform(projection, viewPos, clip);
    if (clip[3] <= 0.0001f)
        return false;
    ndc[0] = clip[0] / clip[3];
    ndc[1] = clip[1] / clip[3];
    ndc[2] = clip[2] / clip[3];
    return ndc[0] > -1.0f && ndc[0] < 1.0f && ndc[1] > -1.0f && ndc[1] < 1.0f && ndc[2] > 0.0f && ndc[2] < 1.0f;
}


// The composition re-attaches its own camera (Cam_MenuLevel) every time it
// launches a scene, so a one-off AttachViewpointToCamera() from LoadLevel() is
// gone by the time the loop draws. Re-attach ours just before every draw, so
// BALLANCE_POC_CAM actually decides what the frame shows.
static void Poc1ApplyCameraPin(CKContext *context, CKRenderContext *rc)
{
    const char *spec = getenv("BALLANCE_POC_CAM");
    if (!spec || !*spec || !context || !rc)
        return;

    CKCamera *camera = (CKCamera *)context->GetObjectByNameAndClass("PoCCamera", CKCID_CAMERA);
    if (!camera)
    {
        camera = rc->GetAttachedCamera();
        if (!camera)
            return;
    }

    rc->AttachViewpointToCamera(camera);
    rc->SetViewTransformationMatrix(camera->GetInverseWorldMatrix());
    VxMatrix projection;
    const float aspect = (rc->GetHeight() > 0) ? (float)rc->GetWidth() / (float)rc->GetHeight() : 4.0f / 3.0f;
    projection.Perspective(camera->GetFov(), aspect, camera->GetFrontPlane(), camera->GetBackPlane());
    rc->SetProjectionTransformationMatrix(projection);
}


// Where is the merged level, really? Entity origins are a poor guide: a map
// mesh can sit at the origin and span hundreds of units. So project the world
// bounding box of every merged 3D entity and report the ones that would cover
// the most screen, plus the level's overall box -- that is what a camera has to
// be aimed at.
static void Poc1BBoxReport(CKContext *context, CKRenderContext *rc, int frame)
{
    if (!getenv("BALLANCE_POC_BBOX") || !context || !rc)
        return;
    if (frame != 60)
        return;

    const VxMatrix &view = rc->GetViewTransformationMatrix();
    const VxMatrix &projection = rc->GetProjectionTransformationMatrix();
    const float halfWidth = rc->GetWidth() * 0.5f;
    const float halfHeight = rc->GetHeight() * 0.5f;

    struct Entry { float area; VxVector center; VxVector size; char name[64]; };
    XArray<Entry> entries;
    VxVector lo(1e30f, 1e30f, 1e30f), hi(-1e30f, -1e30f, -1e30f);

    const XObjectPointerArray entities = context->GetObjectListByType(CKCID_3DENTITY, TRUE);
    for (CKObject **it = entities.Begin(); it != entities.End(); ++it)
    {
        CK3dEntity *entity = (CK3dEntity *)*it;
        if (!entity || !Poc1IsMerged(entity->GetID()))
            continue;

        const VxBbox &box = entity->GetBoundingBox(FALSE);
        if (!box.IsValid())
            continue;

        lo.x = box.Min.x < lo.x ? box.Min.x : lo.x;
        lo.y = box.Min.y < lo.y ? box.Min.y : lo.y;
        lo.z = box.Min.z < lo.z ? box.Min.z : lo.z;
        hi.x = box.Max.x > hi.x ? box.Max.x : hi.x;
        hi.y = box.Max.y > hi.y ? box.Max.y : hi.y;
        hi.z = box.Max.z > hi.z ? box.Max.z : hi.z;

        float sx0 = 1e30f, sy0 = 1e30f, sx1 = -1e30f, sy1 = -1e30f;
        bool anyInside = false;
        for (int corner = 0; corner < 8; ++corner)
        {
            const VxVector point((corner & 1) ? box.Max.x : box.Min.x,
                                 (corner & 2) ? box.Max.y : box.Min.y,
                                 (corner & 4) ? box.Max.z : box.Min.z);
            float ndc[3];
            if (!Poc1InsideFrustum(view, projection, point, ndc))
                continue;
            anyInside = true;
            const float sx = ndc[0] * halfWidth;
            const float sy = ndc[1] * halfHeight;
            sx0 = sx < sx0 ? sx : sx0;
            sx1 = sx > sx1 ? sx : sx1;
            sy0 = sy < sy0 ? sy : sy0;
            sy1 = sy > sy1 ? sy : sy1;
        }
        if (!anyInside)
            continue;

        Entry entry;
        entry.area = (sx1 - sx0) * (sy1 - sy0);
        entry.center = (box.Min + box.Max) * 0.5f;
        entry.size = box.Max - box.Min;
        snprintf(entry.name, sizeof(entry.name), "%s", entity->GetName() ? entity->GetName() : "(unnamed)");
        entries.PushBack(entry);
    }

    CLogger::Get().Debug("PoC bbox: %d merged 3D entity/ies touch the frustum; level box min=(%.1f %.1f %.1f) max=(%.1f %.1f %.1f)",
                         entries.Size(), lo.x, lo.y, lo.z, hi.x, hi.y, hi.z);

    for (int i = 0; i < entries.Size() && i < 12; ++i)
    {
        for (int j = i + 1; j < entries.Size(); ++j)
            if (entries[j].area > entries[i].area)
            {
                Entry swap = entries[i];
                entries[i] = entries[j];
                entries[j] = swap;
            }
        CLogger::Get().Debug("  bbox %-24s area=%8.0f px  center=(%.1f %.1f %.1f) size=(%.1f %.1f %.1f)",
                             entries[i].name, entries[i].area,
                             entries[i].center.x, entries[i].center.y, entries[i].center.z,
                             entries[i].size.x, entries[i].size.y, entries[i].size.z);
    }
}

static void Poc1FrameLog(CKContext *context, CKRenderContext *rc)
{
    const char *spec = getenv("BALLANCE_POC_FRAMELOG");
    if (!spec || !*spec || !context || !rc)
        return;

    static int frame = 0;
    ++frame;
    const int period = atoi(spec) > 1 ? atoi(spec) : 60;
    if (frame > 3 && (frame % period) != 0)
        return;

    CKScene *scene = context->GetCurrentScene();
    CKCamera *camera = rc->GetAttachedCamera();
    CK3dEntity *viewpoint = rc->GetViewpoint();
    VxVector cameraPos(0, 0, 0);
    VxVector viewpointPos(0, 0, 0);
    if (camera)
        camera->GetPosition(&cameraPos);
    if (viewpoint)
        viewpoint->GetPosition(&viewpointPos);

    Poc1BBoxReport(context, rc, frame);

    CLogger::Get().Debug("PoC frame %d: size %dx%d scene=%s camera=%s eye=(%.2f %.2f %.2f) viewpoint=(%.2f %.2f %.2f)",
                         frame, rc->GetWidth(), rc->GetHeight(),
                         scene && scene->GetName() ? scene->GetName() : "(none)",
                         camera && camera->GetName() ? camera->GetName() : "(none)",
                         cameraPos.x, cameraPos.y, cameraPos.z,
                         viewpointPos.x, viewpointPos.y, viewpointPos.z);

    const VxMatrix &view = rc->GetViewTransformationMatrix();
    const VxMatrix &projection = rc->GetProjectionTransformationMatrix();

    const XObjectPointerArray entities = context->GetObjectListByType(CKCID_3DENTITY, TRUE);
    int total = 0, visible = 0, attached = 0, inside = 0;
    int merged3d = 0, mergedVisible = 0, mergedAttached = 0, mergedInside = 0, mergedInScene = 0;
    int logged = 0;
    VxVector lo(1e30f, 1e30f, 1e30f);
    VxVector hi(-1e30f, -1e30f, -1e30f);

    for (CKObject **it = entities.Begin(); it != entities.End(); ++it)
    {
        CK3dEntity *entity = (CK3dEntity *)*it;
        if (!entity)
            continue;

        const bool merged = Poc1IsMerged(entity->GetID());
        const VxVector &world = entity->GetWorldMatrix()[3];
        const bool shown = entity->IsVisible();
        const bool bound = rc->IsObjectAttached(entity) != FALSE;
        float ndc[3];
        const bool inView = Poc1InsideFrustum(view, projection, world, ndc);

        ++total;
        if (shown) ++visible;
        if (bound) ++attached;
        if (inView) ++inside;

        if (merged)
        {
            ++merged3d;
            if (shown) ++mergedVisible;
            if (bound) ++mergedAttached;
            if (inView) ++mergedInside;
            if (scene && entity->IsInScene(scene)) ++mergedInScene;
            lo.x = world.x < lo.x ? world.x : lo.x;
            lo.y = world.y < lo.y ? world.y : lo.y;
            lo.z = world.z < lo.z ? world.z : lo.z;
            hi.x = world.x > hi.x ? world.x : hi.x;
            hi.y = world.y > hi.y ? world.y : hi.y;
            hi.z = world.z > hi.z ? world.z : hi.z;

            if (logged < 12)
            {
                CLogger::Get().Debug("  merged %s cid=%d pos=(%.2f %.2f %.2f) visible=%d attached=%d inScene=%d ndc=(%.2f %.2f %.2f) in=%d",
                                     entity->GetName() ? entity->GetName() : "(unnamed)", entity->GetClassID(),
                                     world.x, world.y, world.z, (int)shown, (int)bound,
                                     scene ? (int)entity->IsInScene(scene) : -1,
                                     ndc[0], ndc[1], ndc[2], (int)inView);
                ++logged;
            }
        }
    }

    CLogger::Get().Debug("PoC frame %d: 3d total=%d visible=%d attached=%d inFrustum=%d | merged 3d=%d visible=%d attached=%d inScene=%d inFrustum=%d",
                         frame, total, visible, attached, inside,
                         merged3d, mergedVisible, mergedAttached, mergedInScene, mergedInside);
    if (merged3d > 0)
        CLogger::Get().Debug("PoC frame %d: merged bounds min=(%.2f %.2f %.2f) max=(%.2f %.2f %.2f)",
                             frame, lo.x, lo.y, lo.z, hi.x, hi.y, hi.z);
}

// Input probe: does the X pointer reach the game at all? The menu is driven by
// TT PushButton2, which needs (a) the mouse position the engine reports and
// (b) the left button going down while the cursor is over the button sprite.
// Neither is visible from outside, so log both.
//
//   BALLANCE_POC_INPUTLOG=<n>   every n-th render call (n<1 -> 30)
static void Poc1InputLog(CKContext *ctx, CKRenderContext *rc)
{
    static int calls = 0;
    ++calls;

    const char *spec = getenv("BALLANCE_POC_INPUTLOG");
    if (!spec || !ctx)
        return;

    int every = atoi(spec);
    if (every < 1)
        every = 30;
    if (calls % every)
        return;

    CKInputManager *im = (CKInputManager *)ctx->GetManagerByGuid(INPUT_MANAGER_GUID);
    if (!im)
    {
        CLogger::Get().Debug("PoC input %d: no input manager", calls);
        return;
    }

    Vx2DVector rel, abs;
    im->GetMousePosition(rel, FALSE);
    im->GetMousePosition(abs, TRUE);

    const char *picked = "(none)";
    if (rc)
    {
        VxRect screen;
        rc->GetWindowRect(screen, FALSE);
        Vx2DVector p = rel;
        p += screen.GetTopLeft();
        CKObject *o = rc->Pick2D(p);
        if (o && o->GetName())
            picked = o->GetName();
        else if (o)
            picked = "(unnamed)";
    }

    CLogger::Get().Debug("PoC input %d: rel=(%.1f %.1f) abs=(%.1f %.1f) left=%d clicked=%d vis=%d pick2d=%s",
                         calls, rel.x, rel.y, abs.x, abs.y,
                         im->IsMouseButtonDown(CK_MOUSEBUTTON_LEFT),
                         im->IsMouseClicked(CK_MOUSEBUTTON_LEFT),
                         im->GetCursorVisibility(), picked);
}

// The menu's TT PushButton2 only reacts while the input manager says the cursor
// is visible: ShowCursor(FALSE) is what the "Mouse Cursor" building block does
// when it draws its own sprite cursor, and in the SDL port that sprite never
// reaches the screen -- so the menu ends up with no cursor and no clicks.
// BALLANCE_POC_CURSOR=1 puts the system cursor back.
static void Poc1ForceCursor(CKContext *ctx)
{
    if (!ctx || !getenv("BALLANCE_POC_CURSOR"))
        return;
    CKInputManager *im = (CKInputManager *)ctx->GetManagerByGuid(INPUT_MANAGER_GUID);
    if (im && !im->GetCursorVisibility())
        im->ShowCursor(TRUE);
}
