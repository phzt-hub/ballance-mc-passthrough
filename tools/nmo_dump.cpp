// nmo_dump - offline Virtools 2.1 (.nmo/.cmo) geometry dumper for Ballance levels.
// Links Ballanced's CK2 reimplementation (CKFile + CKMesh readers).
// Usage:
//   nmo_dump <file> --summary          inventory: id, cid, mesh/vert/face counts + cid histogram
//   nmo_dump <file> --json <out>       full dump: world matrix + vertices + faces per mesh
#include "CKAll.h"

#include <cstdio>
#include <cstring>
#include <map>

static void emit_escaped(FILE *f, const char *s)
{
    fputc('"', f);
    for (const char *p = s; p && *p; ++p)
    {
        unsigned char c = (unsigned char) *p;
        if (c == '"' || c == '\\') { fputc('\\', f); fputc(c, f); }
        else if (c < 0x20) { fprintf(f, "\\u%04x", c); }
        else { fputc(c, f); }
    }
    fputc('"', f);
}

int main(int argc, char **argv)
{
    if (argc < 3)
    {
        fprintf(stderr, "usage: %s <file.nmo> --summary | --json <out.json>\n", argv[0]);
        return 2;
    }
    const char *path = argv[1];
    bool summary = strcmp(argv[2], "--summary") == 0;
    const char *outPath = (!summary && argc >= 4) ? argv[3] : NULL;
    if (!summary && !outPath) { fprintf(stderr, "missing output path\n"); return 2; }

    if (CKStartUp() != CK_OK) { fprintf(stderr, "CKStartUp failed\n"); return 3; }
    CKContext *ctx = NULL;
    if (CKCreateContext(&ctx, NULL, 0, 0) != CK_OK || !ctx) { fprintf(stderr, "CKCreateContext failed\n"); return 3; }

    CKFile file(ctx);
    CKObjectArray *list = CreateCKObjectArray();
    CKERROR err = file.Load((CKSTRING) path, list, CK_LOAD_DEFAULT);
    int fileCount = list ? list->GetCount() : 0;
    int total = ctx->GetObjectCount();
    fprintf(stderr, "load rc=%d file_objects=%d context_objects=%d\n", (int) err, fileCount, total);
    if (err != CK_OK) { CKCloseContext(ctx); CKShutdown(); return 4; }

    FILE *out = summary ? stdout : fopen(outPath, "wb");
    if (!out) { fprintf(stderr, "cannot open output\n"); return 5; }
    if (!summary)
    {
        fprintf(out, "{\n\"file\": ");
        emit_escaped(out, path);
        fprintf(out, ",\n\"objects\": [\n");
    }

    std::map<int, int> cidHist;
    int nEntities = 0, nWithMesh = 0, nMesh = 0, nVert = 0, nFace = 0, first = 1;
    for (int i = 1; i <= total; ++i)
    {
        CKObject *obj = ctx->GetObject((CK_ID) i);
        if (!obj) continue;
        CKSTRING name = obj->GetName();
        CK_CLASSID cid = obj->GetClassID();
        cidHist[(int) cid]++;
        CK3dEntity *ent = CKIsChildClassOf(obj, CKCID_3DENTITY) ? (CK3dEntity *) obj : NULL;
        int meshCount = ent ? ent->GetMeshCount() : 0;
        if (ent) ++nEntities;
        if (meshCount > 0) ++nWithMesh;

        if (summary)
        {
            if (ent && meshCount > 0)
            {
                int vc = 0, fc = 0;
                for (int m = 0; m < meshCount; ++m)
                {
                    CKMesh *mesh = ent->GetMesh(m);
                    if (!mesh) continue;
                    vc += mesh->GetVertexCount();
                    fc += mesh->GetFaceCount();
                }
                printf("id=%-6d cid=%-3d meshes=%d verts=%-6d faces=%-6d name=%s\n",
                       (int) i, (int) cid, meshCount, vc, fc, name ? name : "");
            }
            continue;
        }

        if (!first) fprintf(out, ",\n");
        first = 0;
        fprintf(out, " {\"id\": %d, \"cid\": %d, \"name\": ", (int) i, (int) cid);
        emit_escaped(out, name ? name : "");
        fprintf(out, ", \"is3d\": %s", ent ? "true" : "false");
        if (ent && meshCount > 0)
        {
            const VxMatrix wm = ent->GetWorldMatrix();
            fprintf(out, ", \"world\": [");
            for (int r = 0; r < 4; ++r)
                for (int c = 0; c < 4; ++c)
                    fprintf(out, "%s%.6f", (r || c) ? ", " : "", wm[r][c]);
            fprintf(out, "], \"meshes\": [");
            for (int m = 0; m < meshCount; ++m)
            {
                CKMesh *mesh = ent->GetMesh(m);
                if (!mesh) continue;
                ++nMesh;
                int vc = mesh->GetVertexCount();
                int fc = mesh->GetFaceCount();
                nVert += vc;
                nFace += fc;
                if (m) fprintf(out, ", ");
                fprintf(out, "{\"vertexCount\": %d, \"faceCount\": %d, \"vertices\": [", vc, fc);
                for (int v = 0; v < vc; ++v)
                {
                    VxVector p;
                    mesh->GetVertexPosition(v, &p);
                    fprintf(out, "%s[%.4f, %.4f, %.4f]", v ? ", " : "", p.x, p.y, p.z);
                }
                fprintf(out, "], \"faces\": [");
                for (int fi = 0; fi < fc; ++fi)
                {
                    int a = 0, b = 0, c = 0;
                    mesh->GetFaceVertexIndex(fi, a, b, c);
                    fprintf(out, "%s[%d, %d, %d]", fi ? ", " : "", a, b, c);
                }
                fprintf(out, "]}");
            }
            fprintf(out, "]");
        }
        fprintf(out, "}");
    }

    if (summary)
    {
        printf("-- objects=%d entities=%d with_mesh=%d\n", total, nEntities, nWithMesh);
        printf("-- cid histogram:");
        for (std::map<int, int>::const_iterator it = cidHist.begin(); it != cidHist.end(); ++it)
            printf(" %d=%d", it->first, it->second);
        printf("\n");
    }
    else
    {
        fprintf(out, "\n]}\n");
        fclose(out);
        fprintf(stderr, "dumped objects=%d meshes=%d verts=%d faces=%d -> %s\n", total, nMesh, nVert, nFace, outPath);
    }

    DeleteCKObjectArray(list);
    CKCloseContext(ctx);
    CKShutdown();
    return 0;
}
