#!/usr/bin/env python3
"""Proofs for the Minecraft HUD milestone.

Every claim gets a control that differs in exactly one variable:

  the world is untouched when the HUD is off   none vs hud (only the HUD moved)
  Steve's model is not on screen               none / hud: no shirt-teal pixels
  his model still exists when asked            body vs none: a blob of him
  the crosshair is a white plus at the centre  hud: centre column/row white
  the hotbar is nine dark slots                hud: nine dark runs in one strip
  ten hearts, one per life                     hud: ten components
  the selected slot moves with the number key  hud vs slot: two clusters
  the first-person arm exists                  fp: sleeve and skin pixels
  the HUD does not shimmer                     hud shot2 vs shot3

Steve's model is identified by his shirt teal, not by the whole palette: the
level's rock is exactly the shade of a half-lit skin pixel, so a shade-invariant
match over the full image cannot tell him apart from the ground. Teal (g == b,
red near zero) has no such twin in these levels -- measure it and the empty
frames give exactly zero.
"""
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

SHIRT = (0, 175, 175)
SKIN = (198, 141, 107)
HEART_LIT = (220, 32, 32)
HEART_EMPTY = (70, 20, 20)
SLOT = 20  # units per hotbar slot, before the HUD scale


def shot(tag_dir, name="shot2.png"):
    return np.asarray(Image.open(Path(tag_dir) / name).convert("RGB")).astype(np.int16)


def changed(a, b, threshold=8):
    return np.abs(a - b).max(axis=2) > threshold


def count(img, colour, tolerance=6):
    return int((np.abs(img - np.array(colour)).max(axis=2) <= tolerance).sum())


def teal(img):
    """Steve's shirt/sleeve colour, dark or light, exact or shaded."""
    red, green, blue = img[:, :, 0], img[:, :, 1], img[:, :, 2]
    return (np.abs(green - blue) <= 14) & (green >= 40) & (red <= 0.3 * green)


def runs(flags):
    """(start, end) of every run of True, inclusive."""
    out, start = [], None
    for i, flag in enumerate(flags):
        if flag and start is None:
            start = i
        elif not flag and start is not None:
            out.append((start, i - 1))
            start = None
    if start is not None:
        out.append((start, len(flags) - 1))
    return out


def components(mask, minimum_area=12):
    """Bounding boxes of 8-connected blobs, small specks dropped."""
    seen = np.zeros_like(mask)
    boxes = []
    height, width = mask.shape
    for y, x in zip(*np.nonzero(mask)):
        if seen[y, x]:
            continue
        stack, area = [(y, x)], 0
        seen[y, x] = True
        x0 = x1 = x
        y0 = y1 = y
        while stack:
            cy, cx = stack.pop()
            area += 1
            x0, x1, y0, y1 = min(x0, cx), max(x1, cx), min(y0, cy), max(y1, cy)
            for ny in (cy - 1, cy, cy + 1):
                for nx in (cx - 1, cx, cx + 1):
                    if 0 <= ny < height and 0 <= nx < width and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
        if area >= minimum_area:
            boxes.append((x0, y0, x1, y1, area))
    return boxes


def main(outdir):
    out = Path(outdir)
    none, body = shot(out / "none"), shot(out / "body")
    hud, slot, fp = shot(out / "hud"), shot(out / "slot"), shot(out / "fp")
    height, width = none.shape[:2]
    centre_x, centre_y = width // 2, height // 2
    bottom = int(height * 0.88)
    unit = min(width, height) / 480.0

    failures = []

    checks_made = [0]

    def check(name, ok, detail):
        checks_made[0] += 1
        print(("PASS  " if ok else "FAIL  ") + name + "  " + detail)
        if not ok:
            failures.append(name)

    # 1. Steve's model is nowhere by default, and still drawable on demand.
    check("steve model off by default", teal(none).sum() == 0 and teal(hud).sum() == 0,
          "%d / %d shirt pixels" % (int(teal(none).sum()), int(teal(hud).sum())))
    check("steve model still drawable",
          teal(body).sum() > 500 and changed(none, body).sum() > 2000,
          "%d shirt pixels, %d px changed against the empty frame"
          % (int(teal(body).sum()), int(changed(none, body).sum())))

    # 2. The HUD is there, and it is only the HUD.
    moved = changed(none, hud)
    band = int(moved[bottom:, :].sum())
    above = int(moved[:bottom, :].sum())
    check("hud draws in the bottom band", band > 500, "%d px below y=%d" % (band, bottom))
    check("hud leaves the world alone", above < band,
          "%d px above the band vs %d inside it" % (above, band))

    # 3. Crosshair: a white plus, one pixel thick, centred.
    arm = int(9 * unit)
    vertical = hud[centre_y - arm:centre_y + arm + 1, centre_x, 0]
    horizontal = hud[centre_y, centre_x - arm:centre_x + arm + 1, 0]
    vertical_none = none[centre_y - arm:centre_y + arm + 1, centre_x, 0]
    check("crosshair is a white plus at the centre",
          int((vertical >= 190).sum()) >= 2 * arm - 1 and int((horizontal >= 190).sum()) >= 2 * arm - 1
          and int((vertical_none >= 190).sum()) < arm,
          "vertical %d/%d px, horizontal %d/%d px white, %d of the vertical px were already bright"
          % (int((vertical >= 190).sum()), 2 * arm + 1, int((horizontal >= 190).sum()),
             2 * arm + 1, int((vertical_none >= 190).sum())))

    # 4. Hotbar: nine dark slots in one strip. The row to read is the one with the
    # most separate dark runs -- the experience bar above it is one long strip.
    dark = (hud[bottom:, :] <= 45).all(axis=2)
    dark_none = (none[bottom:, :] <= 45).all(axis=2)
    best_row, best_slots = 0, []
    for row in range(dark.shape[0]):
        found = [r for r in runs(dark[row]) if r[1] - r[0] >= 12]
        if len(found) > len(best_slots):
            best_row, best_slots = row, found
    hotbar_y = bottom + best_row
    check("hotbar is nine dark slots",
          len(best_slots) == 9 and int(dark.sum()) > int(dark_none.sum()) + 1200,
          "%d slots at y=%d, %d px dark vs %d without the HUD"
          % (len(best_slots), hotbar_y, int(dark.sum()), int(dark_none.sum())))

    # 5. Hearts: ten of them, lit ones = one life each.
    hearts = components((np.abs(hud - np.array(HEART_LIT)).max(axis=2) <= 8) |
                        (np.abs(hud - np.array(HEART_EMPTY)).max(axis=2) <= 8))
    lit_hearts = components(np.abs(hud - np.array(HEART_LIT)).max(axis=2) <= 8)
    # 8b. The HudScale row scales the whole HUD, not just the text.
    scaled = shot(out / "scale")
    wide = changed(none, hud)[bottom:, :].any(axis=0)
    wide = np.nonzero(wide)[0]
    size = scaled_span = 0
    wide_scaled = np.nonzero(changed(none, scaled)[bottom:, :].any(axis=0))[0]
    if wide.size and wide_scaled.size:
        size = int(wide.max() - wide.min() + 1)
        scaled_span = int(wide_scaled.max() - wide_scaled.min() + 1)
    ratio = (scaled_span / size) if size else 0.0
    check("HudScale doubles the HUD", 1.75 <= ratio <= 2.25,
          "band spans %d px at scale 1 and %d px at scale 2 (x%.2f)" % (size, scaled_span, ratio))

    log_text = (out / "hud.log").read_text(errors="replace")
    lives = re.search(r"gameplay: points=(-?\d+) lives=(-?\d+)", log_text)
    lives = int(lives.group(2)) if lives else None
    check("ten hearts, one per life",
          len(hearts) == 10 and len(lit_hearts) >= 1 and count(none, HEART_LIT) == 0
          and (lives is None or len(lit_hearts) == lives),
          "%d hearts, %d lit, lives=%s from the level, %d red px without the HUD"
          % (len(hearts), len(lit_hearts), lives, count(none, HEART_LIT)))

    # 6. The selection moved from slot 1 to slot 5, and nothing else in the bar did.
    strip = changed(hud, slot)[bottom:, :]
    merged = []
    for a, b in [(a, b) for a, b in runs(strip.any(axis=0)) if b - a >= 2]:
        if merged and a - merged[-1][1] <= 6:
            merged[-1] = (merged[-1][0], b)
        else:
            merged.append((a, b))
    centres = [int((a + b) // 2) for a, b in merged]
    slot_px = int(SLOT * unit)
    bar_left = (width - 9 * slot_px) // 2
    want = [bar_left + slot_px // 2, bar_left + 4 * slot_px + slot_px // 2]
    check("selection follows the number key",
          len(centres) == 2 and abs(centres[0] - want[0]) <= 12
          and abs(centres[1] - want[1]) <= 12,
          "changed columns grouped at %s, slots 1 and 5 sit at %s" % (centres, want))

    # 7. The experience bar sits above the hotbar. Points may be zero (an empty
    # bar), so what is proved is the bar's place, not its fill.
    rows_dark = dark.sum(axis=1)
    above_hotbar = [row for row in range(dark.shape[0])
                    if rows_dark[row] > 90 and row < best_row - 3]
    check("experience bar above the hotbar", len(above_hotbar) >= 3,
          "%d dark rows above y=%d, tallest %d px"
          % (len(above_hotbar), hotbar_y, int(rows_dark[above_hotbar].max()) if above_hotbar else 0))

    # 8. The arm exists in first person and only there.
    sleeve_fp, sleeve_hud = count(fp, SHIRT), count(hud, SHIRT)
    check("first person arm", sleeve_fp > 300 and sleeve_hud == 0,
          "sleeve %d px in fp, %d px in the parked-hud frame" % (sleeve_fp, sleeve_hud))

    # 9. The HUD is not redrawing itself differently every frame.
    hud2 = shot(out / "hud", "shot3.png")
    steady = int(changed(hud, hud2)[bottom:, :].sum())
    check("hud is steady", steady < 200, "%d px changed between two hud frames" % steady)

    log = log_text
    print("---- log")
    for line in log.splitlines():
        if "[mc-hud]" in line or "gameplay:" in line:
            print("   " + line.strip())
    if "energy array" not in log:
        print("   !! the level's Energy array was not found; hearts fell back to full")

    total = checks_made[0]
    print("\n%d/%d PASS" % (total - len(failures), total))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
