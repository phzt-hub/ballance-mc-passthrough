// nmo_inv - dump every object in a Virtools 2.1 file: id, class id, name.
// Links libcmo21 (static). Reads the file object table even for classes the
// library does not implement (scripts, managers), so it works on base.cmo where
// Ballanced's CK2 segfaults.
//
// Usage: nmo_inv <file.cmo|.nmo>
#include <cstdio>
#include <string>
#include "LibCmo/CK2/CKContext.hpp"
#include "LibCmo/CK2/CKFile.hpp"
#include "LibCmo/CK2/CKGlobals.hpp"

using namespace LibCmo;
using namespace LibCmo::CK2;

static std::string narrow(const char8_t* p) {
    return p ? std::string(reinterpret_cast<const char*>(p)) : std::string();
}

int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s <file>\n", argv[0]); return 2; }
    if (CKStartUp() != CKERROR::CKERR_OK) { fprintf(stderr, "CKStartUp failed\n"); return 3; }
    CKContext ctx;
    ctx.SetEncoding({ std::u8string(u8"GBK"), std::u8string(u8"windows-1252") });
    CKFileReader reader(&ctx);
    const CKERROR err = reader.DeepLoad(reinterpret_cast<const char8_t*>(argv[1]));
    const auto& objs = reader.GetFileObjects();
    fprintf(stderr, "DeepLoad(%s) err=%d objects=%zu\n", argv[1], (int) err, objs.size());
    for (auto& fo : objs) {
        printf("id=%-6d cid=%-3d name=%s\n", (int) fo.ObjectId, (int) fo.ObjectCid,
               narrow(fo.Name.c_str()).c_str());
    }
    CKShutdown();
    return 0;
}
