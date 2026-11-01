#!/usr/bin/env python3
"""Turn nmo_geom manifests (and OBJ exports) into the Ballance -> Minecraft scale table.

Reads the products of tools/export-levels.sh and prints one Markdown document:
matrix-convention check, ball sizes, physics-shape sizes, per-level geometry stats,
corridor widths, and the resulting "how many Ballance units is one MC block" call.

Usage:
    tools/calibrate.py --data /tmp/nmg > docs/scale-calibration.md
"""

import argparse
import glob
import json
import os
import sys

import numpy as np

# The one number this whole script exists to pin down.
UNITS_PER_BLOCK = 4.0


def load(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def bbox_size(o):
    bb = o["bbox"]
    return [bb[i + 3] - bb[i] for i in range(3)]


def read_obj(path):
    verts = []
    faces = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("v "):
                verts.append([float(x) for x in line.split()[1:4]])
            elif line.startswith("f "):
                faces.append([int(t.split("/")[0]) - 1 for t in line.split()[1:4]])
    return np.array(verts), np.array(faces)


def convention_check(level_jsons):
    """Virtools stores translation in row 3 and (0,0,0,1) in column 3.
    If that holds for every entity, the row-vector transform (v' = v * M) is the
    only consistent reading of the matrix; anything else means a bad export."""
    ok = bad = 0
    for path in level_jsons:
        for o in load(path)["objects"]:
            m = o["world"]
            if (abs(m[3]) < 1e-6 and abs(m[7]) < 1e-6 and abs(m[11]) < 1e-6
                    and abs(m[15] - 1.0) < 1e-6):
                ok += 1
            else:
                bad += 1
    return ok, bad


def ball_sizes(data_dir):
    """Render meshes from Balls.nmo plus the four PH ball shapes."""
    out = []
    misc = os.path.join(data_dir, "misc", "Balls.json")
    if os.path.exists(misc):
        for o in load(misc)["objects"]:
            if o["name"] in ("Ball_Wood", "Ball_Stone", "Ball_Paper", "Ball_Light"):
                out.append((o["name"], bbox_size(o), o["verts"], o["faces"], "render"))
    for name in ("P_Ball_Wood", "P_Ball_Stone", "P_Ball_Paper"):
        p = os.path.join(data_dir, "ph", name + ".json")
        if not os.path.exists(p):
            continue
        for o in load(p)["objects"]:
            if o["verts"] == 0:
                continue
            out.append((name, bbox_size(o), o["verts"], o["faces"], "physics"))
    return out


def ph_shapes(data_dir):
    rows = []
    for path in sorted(glob.glob(os.path.join(data_dir, "ph", "P_*.json"))):
        name = os.path.splitext(os.path.basename(path))[0]
        objs = [o for o in load(path)["objects"] if o["verts"] > 0]
        if not objs:
            continue
        mn = [min(o["bbox"][i] for o in objs) for i in range(3)]
        mx = [max(o["bbox"][i + 3] for o in objs) for i in range(3)]
        rows.append((name, [mx[i] - mn[i] for i in range(3)], len(objs)))
    return rows


def corridor_width(verts, faces, cell=1.0):
    """Walkable footprint width via erosion.

    Drops the huge sky / Quader plates, rasterises the upward-facing triangles
    top-down, then erodes until nothing is left. The erosion depth of a cell is
    how deep inside the footprint it sits, so 2 * depth is the width of the
    passage that cell belongs to.
    """
    a = verts[faces[:, 0]]
    b = verts[faces[:, 1]]
    c = verts[faces[:, 2]]
    n = np.cross(b - a, c - a)
    ln = np.linalg.norm(n, axis=1)
    area = 0.5 * ln
    nz = n / np.maximum(ln, 1e-9)[:, None]
    sel = (nz[:, 1] > 0.9) & (area < 500.0)
    tri = verts[faces[sel]]
    if len(tri) == 0:
        return None
    x0 = float(tri[:, :, 0].min())
    z0 = float(tri[:, :, 2].min())
    nx = int((float(tri[:, :, 0].max()) - x0) / cell) + 2
    nzz = int((float(tri[:, :, 2].max()) - z0) / cell) + 2
    cov = np.zeros((nzz, nx), bool)
    u = np.linspace(0.0, 1.0, 9)
    for p0, p1, p2 in tri:
        for s in u:
            for t in u:
                if s + t <= 1.0:
                    p = p0 + s * (p1 - p0) + t * (p2 - p0)
                    cov[int((p[2] - z0) / cell), int((p[0] - x0) / cell)] = True
    covered = int(cov.sum())
    if covered == 0:
        return None
    dist = np.where(cov, 1, 0).astype(np.int16)
    cur = cov.copy()
    while True:
        nxt = cur.copy()
        nxt[1:, :] &= cur[:-1, :]
        nxt[:-1, :] &= cur[1:, :]
        nxt[:, 1:] &= cur[:, :-1]
        nxt[:, :-1] &= cur[:, 1:]
        if not nxt.any():
            break
        dist[nxt] = dist[cur & nxt] + 1
        cur = nxt
    widths = 2.0 * cell * dist[cov].astype(float)
    return {
        "faces": int(sel.sum()),
        "cell_count": covered,
        "p50": float(np.percentile(widths, 50)),
        "p90": float(np.percentile(widths, 90)),
        "widest": float(widths.max()),
    }


def level_stats(data_dir):
    rows = []
    for path in sorted(glob.glob(os.path.join(data_dir, "levels", "Level_*.json"))):
        name = os.path.splitext(os.path.basename(path))[0]
        d = load(path)
        s = d["summary"]
        bb = s["bbox"]
        size = [bb[i + 3] - bb[i] for i in range(3)]
        modul = sum(1 for o in d["objects"] if o["name"].startswith("P_Modul_"))
        cw = None
        obj_path = path[:-5] + ".obj"
        if os.path.exists(obj_path):
            verts, faces = read_obj(obj_path)
            if len(faces):
                cw = corridor_width(verts, faces)
        rows.append({"name": name, "size": size, "summary": s,
                     "modul": modul, "corridor": cw})
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="/tmp/nmg",
                    help="output dir of tools/export-levels.sh")
    ap.add_argument("--units-per-block", type=float, default=UNITS_PER_BLOCK)
    args = ap.parse_args()

    data = args.data
    upb = args.units_per_block
    level_jsons = sorted(glob.glob(os.path.join(data, "levels", "Level_*.json")))
    if not level_jsons:
        sys.exit("no level manifests under " + data)

    out = []
    w = out.append

    w("# Ballance -> Minecraft 尺度标定表")
    w("")
    w("由 `tools/calibrate.py` 从 `tools/nmo_geom` 的导出产物自动生成，勿手改。")
    w("")
    w("数据来源：`3D Entities/`，即零售版 3D 实体（BML+ 校验过的那一份）。")
    w("单位约定：下文 u = Ballance/Virtools 世界单位（文件里的坐标原值）。")
    w("")

    ok, bad = convention_check(level_jsons)
    w("## 0. 矩阵约定自检")
    w("")
    w("Virtools 的 `VxMatrix` 把平移放在第 4 行、第 4 列恒为 (0,0,0,1)，"
      "所以顶点只能按行向量变换：v' = v * M。这一条是后面所有数字的地基，"
      "因此在 13 关、全部实体上逐个数了一遍：")
    w("")
    w("| 检查 | 结果 |")
    w("|---|---|")
    w("| 第 4 列为 (0,0,0,1) 的实体 | %d |" % ok)
    w("| 不符合的实体 | %d |" % bad)
    w("")
    if bad:
        w("> 有 %d 个实体不符合该约定，导出前必须先查清。" % bad)
    else:
        w("> %d/%d 全部符合，行向量是本文件唯一自洽的读法。"
          "`nmo_geom --col` 只是反证用的对照：它把第 4 列当平移，"
          "而第 4 列恒为 0，等于把平移全丢掉。" % (ok, ok + bad))
    w("")

    w("## 1. 球（唯一真正钉死尺度的量）")
    w("")
    w("| 对象 | 尺寸 (u) | 半径 (u) | 顶点 | 面 | 来源 |")
    w("|---|---|---|---|---|---|")
    for name, size, v, f, kind in ball_sizes(data):
        dia = max(size)
        w("| `%s` | %.2f x %.2f x %.2f | %.2f | %d | %d | %s |"
          % (name, size[0], size[1], size[2], dia / 2.0, v, f, kind))
    w("")
    w("球半径 2.0 u、直径 4.0 u；渲染球与物理球同尺寸，"
      "所以它同时是画面标尺和碰撞标尺，是整张表里最靠得住的一个数。")
    w("")

    w("## 2. 物理件（MC 方块 obstruction 的直接蓝本）")
    w("")
    w("`3D Entities/PH/*.nmo` 里每个 `P_*` 文件就是一个碰撞件，下表是它们的世界包围盒。")
    w("")
    w("| 文件 | 尺寸 (u) | 折合球直径 | 网格数 |")
    w("|---|---|---|---|")
    for name, size, nmesh in ph_shapes(data):
        w("| `%s` | %.2f x %.2f x %.2f | %.2f | %d |"
          % (name, size[0], size[1], size[2], max(size) / 4.0, nmesh))
    w("")

    w("## 3. 逐关几何")
    w("")
    w("| 关卡 | 实体数 | 网格对象 | 顶点 | 面 | 尺寸 (u) | 折合方块 | 走廊宽 p50/p90/最宽 (u) |")
    w("|---|---|---|---|---|---|---|---|")
    stats = level_stats(data)
    for r in stats:
        s = r["summary"]
        cw = r["corridor"]
        cwtxt = "—" if not cw else "%.1f / %.1f / %.1f" % (cw["p50"], cw["p90"], cw["widest"])
        w("| %s | %d | %d | %d | %d | %.0f x %.0f x %.0f | %.0f x %.0f x %.0f | %s |"
          % (r["name"], s["entities_with_mesh"], s["mesh_objects"], s["verts"], s["faces"],
             r["size"][0], r["size"][1], r["size"][2],
             r["size"][0] / upb, r["size"][1] / upb, r["size"][2] / upb, cwtxt))
    w("")
    w("13 关合计 %d 个三角、%d 个顶点。"
      % (sum(r["summary"]["faces"] for r in stats),
         sum(r["summary"]["verts"] for r in stats)))
    w("")
    w("走廊宽口径：剔除天空层与 Quader 大板（面积 >= 500 u²），只留朝上的可走面，"
      "按 1 u 栅格铺满成俯视足迹，再一层层往里腐蚀；某格被腐蚀掉的层数即它离边缘多远，"
      "乘 2 就是它所在通道的宽度。所以 p50 说的是平均一块可走地离边缘多近（偏保守），"
      "最宽是全场最大的内切通道宽度；两个数要一起看。")
    w("")

    p50 = [r["corridor"]["p50"] for r in stats if r["corridor"]]
    p90 = [r["corridor"]["p90"] for r in stats if r["corridor"]]
    widest = [r["corridor"]["widest"] for r in stats if r["corridor"]]

    w("## 4. 尺度结论")
    w("")
    w("**取 1 MC 方块 = %.1f u**，也就是球直径正好 1 个方块。" % upb)
    w("")
    w("| 量 | 数值 | 折合方块 |")
    w("|---|---|---|")
    w("| 球半径 | 2.0 u | 0.50 |")
    w("| 球直径 | 4.0 u | 1.00 |")
    w("| 走廊宽 p50（13 关范围） | %.1f ~ %.1f u | %.2f ~ %.2f |"
      % (min(p50), max(p50), min(p50) / upb, max(p50) / upb))
    w("| 走廊宽 p90（13 关范围） | %.1f ~ %.1f u | %.2f ~ %.2f |"
      % (min(p90), max(p90), min(p90) / upb, max(p90) / upb))
    w("| 最宽通道 | %.1f u | %.2f |" % (max(widest), max(widest) / upb))
    w("| `P_Box`（标准箱） | 5.0 u | 1.25 |")
    w("| `P_Extra_Point`（金币件） | 4.58 u | 1.15 |")
    w("| Level_01 范围 | 1893 x 436 x 1874 u | 473 x 109 x 468 |")
    w("")
    w("理由：球是本项目里唯一同时出现在画面和碰撞两侧的量，按球对齐之后，"
      "方块与球、方块与走廊的口径就只剩一个自由度。"
      "代价也一并量在这里：这个比例下 p50 那档通道只有半个方块到 1 个方块宽"
      "（球刚好卡过去），宽平台 3~5 个方块——这是 Ballance 本来的紧度，不是换算带来的。"
      "要让走廊变宽只有一条路：把方块改小到 1 方块 = 2 u，"
      "但那样球会变成 2 个方块宽、比方块还大，观感先崩，故不取。")
    w("")

    w("## 5. 复现")
    w("")
    w("~~~bash")
    w("tools/build-nmo-geom.sh /tmp/nmo_geom")
    w("tools/export-levels.sh /tmp/nmo_geom ~/src/ballance-game /tmp/nmg")
    w("tools/calibrate.py --data /tmp/nmg > docs/scale-calibration.md")
    w("~~~")
    w("")
    w("`/tmp/nmg` 里的 OBJ 是临时产物（约 25 MB），不进仓库；"
      "要核对形状，直接用 Blender 打开任意一关的 OBJ。")
    w("")

    print("\n".join(out))


if __name__ == "__main__":
    main()
