#!/usr/bin/env python3
"""Compare two screenshots of the Ballance window.

    ./diff-shots.py <a.png> <b.png> [--threshold 8]

Prints how many pixels differ (absolute channel delta above the threshold, per
channel), the mean absolute difference, and the same numbers for the four
quadrants. Used by the M0/M0b acceptance runs where the expected difference is
either "the foreign frame replaced the scene" (large) or "nothing happened"
(rounding noise).
"""
import sys

try:
    from PIL import Image, ImageChops
except ImportError:
    raise SystemExit("Pillow is required")


def load(path):
    image = Image.open(path).convert("RGBA")
    return image


def report(label, a, b, threshold):
    diff = ImageChops.difference(a, b)
    pixels = list(diff.getdata())
    total = len(pixels)
    changed = sum(1 for r, g, b_, a_ in pixels if max(r, g, b_) > threshold)
    mean = sum(r + g + b_ for r, g, b_, a_ in pixels) / (3.0 * max(1, total))
    print(f"{label}: changed {changed * 100.0 / max(1, total):5.2f}%  mean {mean:6.3f}")
    return changed * 100.0 / max(1, total)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    threshold = 8
    for arg in sys.argv[1:]:
        if arg.startswith("--threshold"):
            threshold = int(arg.split("=", 1)[1]) if "=" in arg else 8
    if len(args) != 2:
        raise SystemExit(__doc__)
    a, b = load(args[0]), load(args[1])
    if a.size != b.size:
        print(f"size differs: {a.size} vs {b.size}")
        b = b.resize(a.size)
    print(f"== {args[0]} vs {args[1]} ({a.size[0]}x{a.size[1]}, threshold {threshold})")
    report("whole", a, b, threshold)
    w, h = a.size
    for name, box in {
        "top-left": (0, 0, w // 2, h // 2),
        "top-right": (w // 2, 0, w, h // 2),
        "bottom-left": (0, h // 2, w // 2, h),
        "bottom-right": (w // 2, h // 2, w, h),
    }.items():
        report(name, a.crop(box), b.crop(box), threshold)


if __name__ == "__main__":
    main()
