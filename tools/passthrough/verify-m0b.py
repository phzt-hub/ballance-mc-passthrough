#!/usr/bin/env python3
"""Verify an M0b acceptance run: tools/passthrough/test-m0b.sh <tag> [secs] [on|off].

    ./verify-m0b.py /tmp/m0b/fixed-on [--control /tmp/m0b/fixed-off]

Three questions, answered from the PNGs the run left behind:

  flicker   do all the samples of the Ballance window look alike?  The producer
            (a software-rendered Minecraft) is slower than the game, so a consumer
            that only draws on frames carrying new pixels strobes.  Identical
            samples mean the cached texture is re-presented every frame.
  fidelity  is the overlay the same pixels the producer published?  The game's own
            2D interface is deliberately drawn ON TOP of the overlay, so only the
            left/right bands are expected to match exactly; a global gain, a channel
            swap or a vertical flip would show up there too.
  control   with the consumer off the same window shows the game's own scene, so
            on-vs-off must differ across most of the frame.
"""
import argparse
import glob
import sys

import numpy as np
from PIL import Image


def load(path, size=None):
    image = Image.open(path).convert("RGB")
    if size and image.size != size:
        image = image.resize(size, Image.BILINEAR)
    return np.asarray(image).astype(np.float64)


def compare(a, b, name, bands=True):
    difference = np.abs(a - b)
    print("  %-16s whole: mean %6.2f  >8 %6.2f%%" % (
        name, difference.mean(), 100.0 * (difference.max(axis=2) > 8).mean()))
    if bands:
        height, width = difference.shape[:2]
        edge = np.concatenate([difference[:, :64], difference[:, -64:]], axis=1)
        print("  %-16s edges: mean %6.2f  >8 %6.2f%%  max %3.0f" % (
            "", edge.mean(), 100.0 * (edge.max(axis=2) > 8).mean(), edge.max()))
    return difference.mean()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory")
    parser.add_argument("--control", default=None)
    args = parser.parse_args()

    shots = sorted(glob.glob(args.directory + "/shot*.png"))
    if not shots:
        print("no shots in " + args.directory)
        return 1
    frames = [load(path) for path in shots]
    size = Image.open(shots[0]).size
    print("%d samples of %dx%d" % (len(frames), size[0], size[1]))

    print("flicker (samples must look alike):")
    worst = 0.0
    for index in range(1, len(frames)):
        worst = max(worst, compare(frames[0], frames[index], "sample%d vs 1" % (index + 1)))
    print("  worst sample-to-sample mean difference %.2f  -> %s" % (
        worst, "steady" if worst < 1.0 else "UNSTEADY"))

    published = sorted(glob.glob(args.directory + "/mc[0-9]*.png") +
                       glob.glob(args.directory + "/mc-frame.png") +
                       glob.glob(args.directory + "/pat[0-9]*.png"))
    if published:
        print("fidelity vs the published frame (edges must be exact):")
        for path in published[:3]:
            compare(frames[0], load(path, size), path.split("/")[-1])

    if args.control:
        controls = sorted(glob.glob(args.control + "/shot*.png"))
        if controls:
            print("control (passthrough off, same window):")
            compare(frames[0], load(controls[0], size), "on vs off", bands=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
