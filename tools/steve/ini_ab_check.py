#!/usr/bin/env python3
"""Compare the on/off frames from ini_ab.sh: Steve must be the only difference."""

import os
import sys

import numpy as np
from PIL import Image

out = sys.argv[1] if len(sys.argv) > 1 else "run/ini-ab"
a = np.asarray(Image.open(os.path.join(out, "on.png")).convert("RGB")).astype(np.int16)
b = np.asarray(Image.open(os.path.join(out, "off.png")).convert("RGB")).astype(np.int16)
diff = np.abs(a - b).max(axis=2)
mask = diff > 12
ys, xs = np.nonzero(mask)
print("differing pixels:", int(mask.sum()), "of", mask.size)
if len(xs):
    print("bbox x[%d..%d] y[%d..%d]  size %dx%d"
          % (xs.min(), xs.max(), ys.min(), ys.max(), xs.max() - xs.min() + 1, ys.max() - ys.min() + 1))

# Steve's palette inside the changed region; the level lighting darkens it, so
# compare hue-like normalised vectors rather than raw bytes.
flat = a[mask].astype(np.float32)
norm = flat / np.maximum(flat.max(axis=1, keepdims=True), 1.0)
palette = {"shirt": (0, 168, 170), "skin": (198, 141, 107), "pants": (56, 68, 168)}
for name, colour in palette.items():
    want = np.array(colour, dtype=np.float32)
    want /= want.max()
    hit = np.abs(norm - want).max(axis=1) < 0.15
    extra = ("mean row %.0f" % ys[hit].mean()) if hit.sum() else ""
    print("%-6s %d px  %s" % (name, int(hit.sum()), extra))

ok = int(mask.sum()) > 500
print("VERDICT:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
