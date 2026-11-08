#!/usr/bin/env python3
"""Turn an exported Ballance level (OBJ from tools/nmo_geom) into MC block cells.

Why this exists: the passthrough world is empty, so nothing in it can carry a
block -- redstone dust, rails and torches all refuse to sit on air.  The fix the
user picked is to read the level's own surfaces out of the game files and put a
layer of invisible barrier blocks just under them: the picture stays Ballance's,
but the world now has a floor at the right place.

Geometry comes from tools/export-levels.sh (libcmo21), which writes world-space
OBJ with one "o <entity>" group per 3D entity.

Two kinds of faces are kept:

  walkable  upward facing (ny > 0.85) and small enough (< 500 u^2 -- the sky
            layer and the big Quader plates are one triangle each and would
            otherwise become a floor at the top of the map)
  bulk      anything else whose footprint is within DILATE cells of a walkable
            one: walls, columns, the transformer base -- the things the user
            called "big pieces"

Every sample point becomes one cell.  The vertical index for a walkable face is
chosen so that the cell's *top* face is the grid plane nearest the surface, i.e.
the block sits as close to the surface as the grid allows (|error| <= cell/2).
Always rounding down instead would leave the ball floating up to a full block
above the floor.

The baked indices are absolute Ballance-unit coordinates divided by the cell
size (floor), so the Minecraft side only has to subtract the snapped origin.

Usage:
  voxelize.py --data /tmp/nmg/levels --out /tmp/vox [--cell 5.48] [--levels 1,8]
              [--preview]
"""
import argparse
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np

MAGIC = b"MPVX2\0\0\0"
WALKABLE_NY = 0.5         # up to ~60 degrees of slope; Ballance has steeper ramps than 0.85
BIG_FACE_AREA = 500.0     # u^2; the sky layer / overview plates
DILATE_CELLS = 2          # "near the track" for bulk geometry
REACH = 0.72              # cells: half-diagonal, i.e. conservative rasterisation


def read_obj(path):
    """-> list of (entity name, verts[3,3] per face) land handled below.

    Returns verts (N,3) float32, faces (F,3) int32, face_object (F,) int32,
    object names.
    """
    verts = []
    faces = []
    face_object = []
    names = []
    cur = -1
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("v "):
                parts = line.split()
                verts.append((float(parts[1]), float(parts[2]), float(parts[3])))
            elif line.startswith("f "):
                idx = [int(p.split("/")[0]) - 1 for p in line.split()[1:]]
                for k in range(1, len(idx) - 1):
                    faces.append((idx[0], idx[k], idx[k + 1]))
                    face_object.append(cur)
            elif line.startswith("o "):
                cur += 1
                names.append(line[2:].strip())
    return (np.asarray(verts, np.float64), np.asarray(faces, np.int32),
            np.asarray(face_object, np.int32), names)


def face_geometry(verts, faces):
    p0 = verts[faces[:, 0]]
    p1 = verts[faces[:, 1]]
    p2 = verts[faces[:, 2]]
    n = np.cross(p1 - p0, p2 - p0)          # length = 2 * area
    length = np.linalg.norm(n, axis=1)
    return p0, p1, p2, n, length * 0.5


def bary_samples(p0, p1, p2, cell, cache):
    """Sample points on a triangle, dense enough that no cell is skipped."""
    max_edge = max(np.linalg.norm(p1 - p0), np.linalg.norm(p2 - p1),
                   np.linalg.norm(p0 - p2))
    n = int(math.ceil(max_edge / (cell * 0.45)))
    n = max(n, 1)
    n = min(n, 64)
    grid = cache.get(n)
    if grid is None:
        a = []
        b = []
        for i in range(n + 1):
            for j in range(n + 1 - i):
                a.append(i / n)
                b.append(j / n)
        a = np.asarray(a, np.float64)[:, None]
        b = np.asarray(b, np.float64)[:, None]
        grid = (a, b, 1.0 - a - b)
        cache[n] = grid
    a, b, c = grid
    pts = a * p1 + b * p2 + c * p0
    return pts


def _seg_dist(px, pz, x0, z0, x1, z1):
    dx = x1 - x0
    dz = z1 - z0
    l2 = dx * dx + dz * dz
    t = np.clip(((px - x0) * dx + (pz - z0) * dz) / max(l2, 1e-9), 0.0, 1.0)
    return np.hypot(px - (x0 + t * dx), pz - (z0 + t * dz))


def _tri_dist(px, pz, tri):
    (x0, z0), (x1, z1), (x2, z2) = tri
    d1 = (px - x1) * (z0 - z1) - (x0 - x1) * (pz - z1)
    d2 = (px - x2) * (z1 - z2) - (x1 - x2) * (pz - z2)
    d3 = (px - x0) * (z2 - z0) - (x2 - x0) * (pz - z0)
    inside = ~(((d1 < 0) | (d2 < 0) | (d3 < 0)) & ((d1 > 0) | (d2 > 0) | (d3 > 0)))
    d = np.minimum(np.minimum(_seg_dist(px, pz, x0, z0, x1, z1),
                              _seg_dist(px, pz, x1, z1, x2, z2)),
                   _seg_dist(px, pz, x2, z2, x0, z0))
    return np.where(inside, 0.0, d)


def raster_floor(p0, p1, p2, n, cell):
    """Cells whose centre is within REACH*cell of the face, with y from its plane.

    Sampling points on the triangle (the first version) missed the narrow ones:
    a 4 u track is 0.73 cells wide, so a diagonal run of it fell between sample
    points and the floor came out dotted.  Distance to the projected triangle is
    the honest version of "this cell is under the surface".
    """
    xs = np.array([p0[0], p1[0], p2[0]], np.float64)
    ys = np.array([p0[1], p1[1], p2[1]], np.float64)
    zs = np.array([p0[2], p1[2], p2[2]], np.float64)
    i0 = int(math.floor((xs.min() - cell) / cell))
    i1 = int(math.floor((xs.max() + cell) / cell))
    k0 = int(math.floor((zs.min() - cell) / cell))
    k1 = int(math.floor((zs.max() + cell) / cell))
    if i1 < i0 or k1 < k0:
        return []
    ii, kk = np.meshgrid(np.arange(i0, i1 + 1), np.arange(k0, k1 + 1))
    cx = (ii + 0.5) * cell
    cz = (kk + 0.5) * cell
    tri = [(xs[0], zs[0]), (xs[1], zs[1]), (xs[2], zs[2])]
    keep = _tri_dist(cx, cz, tri) <= REACH * cell
    if not keep.any():
        return []
    ny = n[1]
    if abs(ny) < 1e-6:
        npk = 0.0
    else:
        npk = -(n[0] * (cx - p0[0]) + n[2] * (cz - p0[2])) / ny + p0[1]
    npk = npk
    ci = ii[keep]
    ck = kk[keep]
    cy = npk[keep]
    # The cell's TOP face must stay at or below the surface. Rounding to the nearest plane
    # instead lets a block stick up to half a cell above the floor -- measured on level 1's
    # start, that put the ball's own cell inside a barrier (the ball sits at y=17.7 u and the
    # nearest plane was 21.9), and nothing can be placed where the block already is.
    cj = np.floor(cy / cell).astype(np.int64) - 1
    return list(zip(ci.tolist(), cj.tolist(), ck.tolist()))


def voxelize(level_obj, cell, verbose=True):
    verts, faces, face_object, names = read_obj(level_obj)
    p0, p1, p2, normal, area = face_geometry(verts, faces)
    unit_y = np.zeros(len(faces))
    ok = area > 1e-9
    unit_y[ok] = normal[ok, 1] / (area[ok] * 2.0)
    walkable = ok & (unit_y > WALKABLE_NY) & (area < BIG_FACE_AREA)
    if verbose:
        print(f"  faces={len(faces)}  walkable={int(walkable.sum())}  "
              f"other={int((~walkable & ok).sum())}")
    cache = {}
    cells_w = set()
    for f in np.nonzero(walkable)[0]:
        cells_w.update(raster_floor(p0[f], p1[f], p2[f], normal[f], cell))
    if verbose:
        print(f"  walkable cells: {len(cells_w)}")
    # Bulk: keep faces whose footprint cell is within DILATE of a walkable cell.
    if cells_w:
        arr = np.asarray(sorted(cells_w), np.int64)
        xz = set(zip(arr[:, 0].tolist(), arr[:, 2].tolist()))
        near = set()
        for (x, z) in xz:
            for dx in range(-DILATE_CELLS, DILATE_CELLS + 1):
                for dz in range(-DILATE_CELLS, DILATE_CELLS + 1):
                    near.add((x + dx, z + dz))
    else:
        near = set()
    cells_b = set()
    bulk = (~walkable) & ok & (area < BIG_FACE_AREA)
    for f in np.nonzero(bulk)[0]:
        cx = float(p0[f][0] + p1[f][0] + p2[f][0]) / 3.0
        cz = float(p0[f][2] + p1[f][2] + p2[f][2]) / 3.0
        if (int(math.floor(cx / cell)), int(math.floor(cz / cell))) not in near:
            continue
        pts = bary_samples(p0[f], p1[f], p2[f], cell, cache)
        ix = np.floor(pts[:, 0] / cell).astype(np.int64)
        iy = np.floor(pts[:, 1] / cell).astype(np.int64)
        iz = np.floor(pts[:, 2] / cell).astype(np.int64)
        cells_b.update(zip(ix.tolist(), iy.tolist(), iz.tolist()))
    if verbose:
        print(f"  bulk cells: {len(cells_b)}")
    grid = np.unique(np.asarray(sorted(cells_w | cells_b), np.int64).reshape(-1, 3), axis=0)
    walk = np.asarray(sorted(cells_w), np.int64).reshape(-1, 3)
    if verbose and len(walk):
        # How much of the floor is face-connected to the rest of the floor: a
        # dotted floor would take redstone in patches and support nothing well.
        s = set(map(tuple, walk.tolist()))
        linked = 0
        for (i, j, k_) in walk:
            if (((i + 1, j, k_) in s) or ((i - 1, j, k_) in s)
                    or ((i, j, k_ + 1) in s) or ((i, j, k_ - 1) in s)):
                linked += 1
        print(f"  floor face-connected: {linked}/{len(walk)} "
              f"({100.0 * linked / len(walk):.1f}%)")
    return grid, walk, names


def write_bin(path, grid, cell, walk=None):
    mn = grid.min(axis=0)
    rel = (grid - mn).astype(np.int32)
    if rel.max(axis=0).max() > 65535:
        raise SystemExit(f"{path}: level does not fit in uint16 indices")
    extent = (rel.max(axis=0) + 1).astype(np.int64)
    # Walkable cells first: the reader takes the first walkable_count entries as the
    # floor, which is what the level match is measured against.
    order = np.arange(len(grid))
    walk_count = 0
    if walk is not None and len(walk):
        wkeys = set(map(tuple, (walk - mn).tolist()))
        is_walk = np.array([tuple(r) in wkeys for r in rel.tolist()], bool)
        order = np.concatenate([np.nonzero(is_walk)[0], np.nonzero(~is_walk)[0]])
        walk_count = int(is_walk.sum())
    with open(path, "wb") as fh:
        fh.write(MAGIC)
        fh.write(struct.pack("<II f 3f".replace(" ", ""), len(grid), walk_count, float(cell),
                             float(mn[0]), float(mn[1]), float(mn[2])))
        fh.write(struct.pack("<3H", int(extent[0]), int(extent[1]), int(extent[2])))
        fh.write(rel[order].astype("<u2").tobytes())
    return len(grid), mn, extent - 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="/tmp/nmg/levels")
    ap.add_argument("--out", default="/tmp/vox")
    ap.add_argument("--cell", type=float, default=5.48)
    ap.add_argument("--levels", default="")
    ap.add_argument("--preview", action="store_true")
    ap.add_argument("--scale3d", type=float, default=1.0,
                    help="cells per outline for the preview (1 = one grid cell)")
    args = ap.parse_args()

    data = Path(args.data)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    levels = sorted(p.stem for p in data.glob("Level_*.obj"))
    if args.levels:
        want = {f"Level_{int(x):02d}" for x in args.levels.split(",")}
        levels = [lv for lv in levels if lv in want]
    stats = {}
    for lv in levels:
        print(f"== {lv}")
        grid, walk, names = voxelize(data / f"{lv}.obj", args.cell)
        n, mn, mx = write_bin(out / f"{lv}.bin", grid, args.cell, walk)
        blocks = (mx - mn + 1).astype(int)
        stats[lv] = dict(blocks=int(n), walkable=int(len(walk)),
                         extent=[int(v) for v in blocks],
                         origin=[float(v) for v in mn], cell=args.cell,
                         entities=len(names))
        print(f"  blocks={n}  extent={tuple(blocks)}  bin={(out / (lv + '.bin')).stat().st_size} B")
        if args.preview:
            make_preview(out / "preview" / f"{lv}.png", grid, walk, lv)
    (out / "levels.json").write_text(json.dumps(stats, indent=2))
    print(f"\nwrote {out}/levels.json")


def make_preview(path, grid, walk, title, ppc=3, target=1500):
    """Iso view, painter's algorithm: walkable = green, bulk = grey.

    Also writes "<name>-top.png": a top-down map coloured by height with the block
    grid drawn, which is the honest way to see how coarse the cell is.
    """
    from PIL import Image, ImageDraw
    path.parent.mkdir(parents=True, exist_ok=True)
    if len(grid) == 0:
        return
    mn = grid.min(axis=0)
    rel = grid - mn
    walk_set = set(map(tuple, (walk - mn).tolist())) if len(walk) else set()

    # fit ppc so the picture stays under "target" pixels on its long side
    sx = (rel[:, 0] - rel[:, 2]).astype(float)
    sy = ((rel[:, 0] + rel[:, 2]) * 0.5 - rel[:, 1]).astype(float)
    span = max(sx.max() - sx.min() + 2, sy.max() - sy.min() + 2)
    ppc = max(1.0, min(float(ppc), target / span))
    w = int((sx.max() - sx.min() + 2) * ppc) + 4
    h = int((sy.max() - sy.min() + 2) * ppc) + 24
    img = Image.new("RGB", (w, h), (16, 18, 24))
    dr = ImageDraw.Draw(img)
    ox = -(sx.min() - 1) * ppc + 2
    oy = -(sy.min() - 1) * ppc + 20
    order = np.argsort(rel[:, 0] + rel[:, 1] + rel[:, 2])   # far to near
    hw = ppc * 0.5
    for i in order:
        px = sx[i] * ppc + ox
        py = sy[i] * ppc + oy
        top = (int(rel[i, 0]), int(rel[i, 1]), int(rel[i, 2])) in walk_set
        col = (98, 186, 106) if top else (132, 136, 144)
        dark1 = tuple(int(v * 0.66) for v in col)
        dark2 = tuple(int(v * 0.48) for v in col)
        dr.polygon([(px, py), (px + ppc, py + hw), (px, py + ppc), (px - ppc, py + hw)],
                   fill=col)
        dr.polygon([(px - ppc, py + hw), (px, py + ppc), (px, py + ppc + ppc),
                    (px - ppc, py + hw + ppc)], fill=dark1)
        dr.polygon([(px, py + ppc), (px + ppc, py + hw), (px + ppc, py + hw + ppc),
                    (px, py + ppc + ppc)], fill=dark2)
    dr.text((8, 4), f"{title}: {len(grid)} blocks, {ppc:.1f} px/cell, "
                    f"green = walkable surface", fill=(236, 236, 236))
    img.save(path)
    print(f"  preview {path}  {img.size[0]}x{img.size[1]}")

    # top-down, colour by height, one pixel box per cell
    top = Image.new("RGB", (int(rel[:, 0].max()) + 2, int(rel[:, 2].max()) + 2), (16, 18, 24))
    tdr = ImageDraw.Draw(top)
    ymin, ymax = float(rel[:, 1].min()), float(rel[:, 1].max())
    for (x, y, z) in rel:
        t = (y - ymin) / max(1e-6, ymax - ymin)
        if (int(x), int(y), int(z)) in walk_set:
            col = (int(60 + 120 * t), int(170 + 60 * t), int(70 + 40 * t))
        else:
            col = (int(60 + 90 * t), int(64 + 90 * t), int(72 + 90 * t))
        tdr.point((int(x), int(z)), fill=col)
    tw, th = top.size
    k = max(1, min(4, 1600 // max(tw, th)))
    if k > 1:
        top = top.resize((tw * k, th * k), Image.NEAREST)
    top.save(path.with_name(path.stem + "-top.png"))
    print(f"  top     {path.with_name(path.stem + '-top.png')}  {top.size[0]}x{top.size[1]}")


if __name__ == "__main__":
    sys.exit(main())
