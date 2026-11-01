// nmo_geom - export world-space triangle geometry of a Virtools 2.1 file (.nmo/.cmo).
//
// Links libcmo21 (yyc12345) as a static library. CKFileReader::DeepLoad creates every
// object first and only then calls Load() on each one, so by the time we walk the file
// object list every CK3dEntity -> CKMesh reference (CK_STATESAVE_MESHS chunk) is already
// resolved by ReadObjectPointer(); no manual reference fixup is needed.
//
// Usage:
//   nmo_geom <file.nmo> [--obj out.obj] [--json out.json] [--col] [--scale S]
//
//   --obj   world-space triangles, one "o <entity>" group per 3d entity
//   --json  manifest: per entity name / cid / mesh / verts / faces / world matrix / bbox
//   --col   transform with the column-major convention (v' = M * v) instead of the
//           default Virtools row-vector convention (v' = v * M, translation in row 3)
//   --scale multiply every exported coordinate by S (default 1.0)

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <string>
#include <vector>

#include "LibCmo/CK2/CKContext.hpp"
#include "LibCmo/CK2/CKFile.hpp"
#include "LibCmo/CK2/CKGlobals.hpp"
#include "LibCmo/CK2/ObjImpls/CK3dEntity.hpp"
#include "LibCmo/CK2/ObjImpls/CKMesh.hpp"

using namespace LibCmo;
using namespace LibCmo::CK2;

namespace {

struct BBox {
    double mn[3] = { 1e30, 1e30, 1e30 };
    double mx[3] = { -1e30, -1e30, -1e30 };
    bool valid = false;
    void add(double x, double y, double z) {
        valid = true;
        const double v[3] = { x, y, z };
        for (int i = 0; i < 3; ++i) {
            if (v[i] < mn[i]) mn[i] = v[i];
            if (v[i] > mx[i]) mx[i] = v[i];
        }
    }
    void merge(const BBox& o) {
        if (!o.valid) return;
        valid = true;
        for (int i = 0; i < 3; ++i) {
            if (o.mn[i] < mn[i]) mn[i] = o.mn[i];
            if (o.mx[i] > mx[i]) mx[i] = o.mx[i];
        }
    }
};

std::string narrow(const char8_t* p) {
    return p ? std::string(reinterpret_cast<const char*>(p)) : std::string();
}

std::string obj_token(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (unsigned char c : s) {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') out.push_back('_');
        else if (c < 0x20) out.push_back('_');
        else out.push_back(static_cast<char>(c));
    }
    if (out.empty()) out = "unnamed";
    return out;
}

void json_escape(FILE* f, const std::string& s) {
    fputc('"', f);
    for (unsigned char c : s) {
        if (c == 0x22 || c == 0x5c) { fputc(0x5c, f); fputc(c, f); }
        else if (c < 0x20) fprintf(f, "\\u%04x", c);
        else fputc(c, f);
    }
    fputc(0x22, f);
}

struct Xform {
    double m[4][4];
    bool colmajor;
    double scale;
    void apply(double x, double y, double z, double out[3]) const {
        if (colmajor) {
            out[0] = m[0][0] * x + m[0][1] * y + m[0][2] * z + m[0][3];
            out[1] = m[1][0] * x + m[1][1] * y + m[1][2] * z + m[1][3];
            out[2] = m[2][0] * x + m[2][1] * y + m[2][2] * z + m[2][3];
        } else {
            out[0] = x * m[0][0] + y * m[1][0] + z * m[2][0] + m[3][0];
            out[1] = x * m[0][1] + y * m[1][1] + z * m[2][1] + m[3][1];
            out[2] = x * m[0][2] + y * m[1][2] + z * m[2][2] + m[3][2];
        }
        out[0] *= scale; out[1] *= scale; out[2] *= scale;
    }
};

} // namespace

static int run(const char* inPath, const char* objPath, const char* jsonPath, bool colmajor, double scale) {
    if (CKStartUp() != CKERROR::CKERR_OK) { fprintf(stderr, "CKStartUp failed\n"); return 3; }

    {
        CKContext ctx;
        ctx.SetEncoding({ std::u8string(u8"GBK"), std::u8string(u8"windows-1252") });
        CKFileReader reader(&ctx);
        const CKERROR err = reader.DeepLoad(reinterpret_cast<const char8_t*>(inPath));
        if (err != CKERROR::CKERR_OK) {
            fprintf(stderr, "DeepLoad(%s) failed: err=%d\n", inPath, (int) err);
            return 4;
        }

        FILE* obj = nullptr;
        FILE* js = nullptr;
        if (objPath) { obj = fopen(objPath, "wb"); if (!obj) { fprintf(stderr, "cannot write %s\n", objPath); return 5; } }
        if (jsonPath) { js = fopen(jsonPath, "wb"); if (!js) { fprintf(stderr, "cannot write %s\n", jsonPath); return 5; } }

        if (obj) {
            fprintf(obj, "# nmo_geom export: %s\n", inPath);
            fprintf(obj, "# transform: %s, scale %g\n", colmajor ? "column-major v'" "=M*v" : "row-vector v'" "=v*M", scale);
        }
        if (js) {
            fprintf(js, "{\n  \"file\": ");
            json_escape(js, inPath);
            fprintf(js, ",\n  \"convention\": \"%s\",\n  \"scale\": %.9g,\n  \"objects\": [\n",
                colmajor ? "colmajor" : "rowvector", scale);
        }

        long long vtxBase = 0;
        int nEntity = 0, nEntityMesh = 0, nMeshObj = 0, nGroups = 0;
        long long nVert = 0, nVertOut = 0, nFace = 0, nFaceOut = 0, nDropped = 0;
        int colOk = 0, colBad = 0;   // entities whose 4th matrix column is (0,0,0,1)
        BBox total;
        bool firstJson = true;

        auto& objs = reader.GetFileObjects();
        for (auto& fo : objs) {
            if (!fo.ObjPtr) continue;
            if (dynamic_cast<ObjImpls::CKMesh*>(fo.ObjPtr)) ++nMeshObj;

            auto* ent = dynamic_cast<ObjImpls::CK3dEntity*>(fo.ObjPtr);
            if (!ent) continue;
            ++nEntity;
            {
                // Virtools keeps the translation in row 3 and (0,0,0,1) in column 3.
                // If that column is not clean, the row-vector reading below is wrong.
                const VxMath::VxMatrix& w0 = ent->GetWorldMatrix();
                if (std::fabs(w0[0][3]) < 1e-6 && std::fabs(w0[1][3]) < 1e-6
                    && std::fabs(w0[2][3]) < 1e-6 && std::fabs(w0[3][3] - 1.0f) < 1e-6) ++colOk;
                else ++colBad;
            }
            ObjImpls::CKMesh* mesh = ent->GetCurrentMesh();
            if (!mesh) continue;
            ++nEntityMesh;

            const CKDWORD vc = mesh->GetVertexCount();
            const CKDWORD fc = mesh->GetFaceCount();
            nVert += vc; nFace += fc;
            if (vc == 0 || fc == 0) continue;

            const VxMath::VxVector3* vp = mesh->GetVertexPositions();
            const CKWORD* fi = mesh->GetFaceIndices();
            if (!vp || !fi) continue;

            Xform xf;
            xf.colmajor = colmajor;
            xf.scale = scale;
            const VxMath::VxMatrix& wm = ent->GetWorldMatrix();
            for (int r = 0; r < 4; ++r)
                for (int c = 0; c < 4; ++c)
                    xf.m[r][c] = wm[r][c];

            std::vector<double> world(static_cast<size_t>(vc) * 3);
            BBox bb;
            for (CKDWORD v = 0; v < vc; ++v) {
                double p[3];
                xf.apply(vp[v].x, vp[v].y, vp[v].z, p);
                world[v * 3 + 0] = p[0];
                world[v * 3 + 1] = p[1];
                world[v * 3 + 2] = p[2];
                bb.add(p[0], p[1], p[2]);
            }
            total.merge(bb);

            const std::string entName = narrow(ent->GetName());
            const std::string meshName = narrow(mesh->GetName());

            if (obj) {
                fprintf(obj, "o %s\n", obj_token(entName).c_str());
                for (CKDWORD v = 0; v < vc; ++v)
                    fprintf(obj, "v %.4f %.4f %.4f\n", world[v * 3 + 0], world[v * 3 + 1], world[v * 3 + 2]);
                for (CKDWORD f = 0; f < fc; ++f) {
                    const CKWORD a = fi[f * 3 + 0], b = fi[f * 3 + 1], c = fi[f * 3 + 2];
                    if (a >= vc || b >= vc || c >= vc) { ++nDropped; continue; }
                    if (a == b || b == c || a == c) { ++nDropped; continue; }
                    fprintf(obj, "f %lld %lld %lld\n",
                        vtxBase + (long long) a + 1,
                        vtxBase + (long long) b + 1,
                        vtxBase + (long long) c + 1);
                    ++nFaceOut;
                }
                nVertOut += vc;
                vtxBase += vc;
                ++nGroups;
            }

            if (js) {
                if (!firstJson) fprintf(js, ",\n");
                firstJson = false;
                fprintf(js, "    {\"name\": ");
                json_escape(js, entName);
                fprintf(js, ", \"cid\": %d, \"mesh\": ", (int) fo.ObjectCid);
                json_escape(js, meshName);
                fprintf(js, ", \"verts\": %u, \"faces\": %u, \"world\": [", (unsigned) vc, (unsigned) fc);
                for (int r = 0; r < 4; ++r)
                    for (int c = 0; c < 4; ++c)
                        fprintf(js, "%s%.7g", (r || c) ? ", " : "", wm[r][c]);
                fprintf(js, "], \"bbox\": [");
                for (int i = 0; i < 3; ++i) fprintf(js, "%s%.4f", i ? ", " : "", bb.mn[i]);
                for (int i = 0; i < 3; ++i) fprintf(js, ", %.4f", bb.mx[i]);
                fprintf(js, "]}");
            }
        }

        if (js) {
            fprintf(js, "\n  ],\n  \"summary\": {");
            fprintf(js, "\"objects\": %zu, \"mesh_objects\": %d, \"entities\": %d, \"entities_with_mesh\": %d, ",
                objs.size(), nMeshObj, nEntity, nEntityMesh);
            fprintf(js, "\"verts\": %lld, \"faces\": %lld, \"bbox\": [", nVert, nFace);
            for (int i = 0; i < 3; ++i) fprintf(js, "%s%.4f", i ? ", " : "", total.mn[i]);
            for (int i = 0; i < 3; ++i) fprintf(js, ", %.4f", total.mx[i]);
            fprintf(js, "]}\n}\n");
        }
        if (obj) fclose(obj);
        if (js) fclose(js);

        fprintf(stderr, "%s: objects=%zu mesh_objects=%d entities=%d entities_with_mesh=%d verts=%lld faces=%lld dropped_faces=%lld\n",
            inPath, objs.size(), nMeshObj, nEntity, nEntityMesh, nVert, nFace, nDropped);
        fprintf(stderr, "  bbox x[%.2f, %.2f] y[%.2f, %.2f] z[%.2f, %.2f]\n",
            total.mn[0], total.mx[0], total.mn[1], total.mx[1], total.mn[2], total.mx[2]);
        if (obj) fprintf(stderr, "  obj groups=%d verts_written=%lld faces_written=%lld\n", nGroups, nVertOut, nFaceOut);
        fprintf(stderr, "  matrix column 3 == (0,0,0,1): %d/%d entities%s\n",
            colOk, colOk + colBad,
            colBad ? "  <-- WARNING: row-vector transform is not justified" : "");
    }

    return 0;
}


int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <file.nmo> [--obj out.obj] [--json out.json] [--col] [--scale S]\n", argv[0]);
        return 2;
    }
    const char* inPath = argv[1];
    const char* objPath = nullptr;
    const char* jsonPath = nullptr;
    bool colmajor = false;
    double scale = 1.0;

    for (int i = 2; i < argc; ++i) {
        if (!strcmp(argv[i], "--obj") && i + 1 < argc) objPath = argv[++i];
        else if (!strcmp(argv[i], "--json") && i + 1 < argc) jsonPath = argv[++i];
        else if (!strcmp(argv[i], "--col")) colmajor = true;
        else if (!strcmp(argv[i], "--scale") && i + 1 < argc) scale = atof(argv[++i]);
        else { fprintf(stderr, "unknown argument: %s\n", argv[i]); return 2; }
    }
    if (!objPath && !jsonPath) { fprintf(stderr, "need --obj and/or --json\n"); return 2; }

    if (CKStartUp() != CKERROR::CKERR_OK) { fprintf(stderr, "CKStartUp failed\n"); return 3; }

    const int rc = run(inPath, objPath, jsonPath, colmajor, scale);

    CKShutdown();
    return rc;
}
