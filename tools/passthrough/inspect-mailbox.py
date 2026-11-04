#!/usr/bin/env python3
"""Inspect the Ballance <-> Minecraft frame mailbox from the outside.

Reads the shared memory object documented in mpframe.h without involving either
end of the pipeline, which is how M0b is verified: if this tool sees a live
Minecraft frame, the Minecraft side really publishes; if the Ballance window
then shows it, the consumer side really draws.

    ./inspect-mailbox.py                 # one-shot header dump + PNG of the newest slot
    ./inspect-mailbox.py --watch 20      # 20 samples, prints fps and frame-to-frame diff
    ./inspect-mailbox.py --png /tmp/x.png
"""
import argparse
import json
import mmap
import os
import struct
import sys
import time

HEADER = 4096
MAGIC = 0x3050464D


def open_mailbox(name):
    path = name if name.startswith("/") else "/dev/shm/" + name
    if not path.startswith("/dev/shm/") and not path.startswith("/"):
        path = "/dev/shm/" + path
    fd = os.open(path, os.O_RDONLY)
    size = os.fstat(fd).st_size
    if size < HEADER:
        raise SystemExit(f"{path}: only {size} bytes, no header")
    return fd, mmap.mmap(fd, size, mmap.MAP_SHARED, mmap.PROT_READ), path


def read_header(buf):
    magic, version, width, height, stride, fmt, slot_bytes, slots, frame_id, pub_slot, pid, flags, ts = (
        struct.unpack_from("<13I", buf, 0))
    offsets = struct.unpack_from("<3Q", buf, 56)
    return {
        "magic": hex(magic), "magic_ok": magic == MAGIC, "version": version,
        "width": width, "height": height, "stride": stride, "format": fmt,
        "slotBytes": slot_bytes, "slotCount": slots, "frameId": frame_id,
        "publishedSlot": pub_slot, "producerPid": pid, "flags": flags,
        "timestampNs": ts, "slotOffsets": offsets, "bytes": len(buf),
    }


def slot_view(buf, header):
    slot = header["publishedSlot"]
    if slot >= header["slotCount"]:
        raise SystemExit(f"published slot {slot} out of range")
    off = header["slotOffsets"][slot]
    n = header["slotBytes"]
    return buf[off:off + n], header["width"], header["height"], header["stride"]


def write_png(data, width, height, stride, path):
    try:
        from PIL import Image
    except ImportError:
        ppm = os.path.splitext(path)[0] + ".ppm"
        with open(ppm, "wb") as handle:
            handle.write(b"P6\n%d %d\n255\n" % (width, height))
            for y in range(height):
                row = data[y * stride:(y + 1) * stride]
                handle.write(b"".join(bytes((row[x * 4 + 2], row[x * 4 + 1], row[x * 4]))
                                      for x in range(width)))
        return ppm
    image = Image.frombytes("RGBA", (width, height), bytes(data), "raw", "BGRA")
    image = image.convert("RGB")
    image.save(path)
    return path


def stats(data, width, height, stride):
    """Coarse content signature: mean colour, spread, and a few probe pixels."""
    step_x = max(1, width // 8)
    step_y = max(1, height // 8)
    total = [0, 0, 0]
    count = 0
    for y in range(0, height, step_y):
        for x in range(0, width, step_x):
            i = y * stride + x * 4
            total[0] += data[i + 2]
            total[1] += data[i + 1]
            total[2] += data[i]
            count += 1
    probes = {}
    for label, (px, py) in {"topleft": (0, 0), "topright": (width - 1, 0),
                            "bottomleft": (0, height - 1), "centre": (width // 2, height // 2)}.items():
        i = py * stride + px * 4
        probes[label] = [data[i + 2], data[i + 1], data[i]]
    return {"mean": [round(c / max(1, count), 1) for c in total], "probes": probes}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mailbox", default=os.environ.get("BALLANCE_MC_SHM", "ballance_mc_frame"))
    parser.add_argument("--png", default=None)
    parser.add_argument("--watch", type=int, default=0, help="seconds to sample")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    fd, buf, path = open_mailbox(args.mailbox)
    try:
        header = read_header(buf)
        if args.png:
            data, w, h, stride = slot_view(buf, header)
            written = write_png(data, w, h, stride, args.png)
            header["png"] = written
        header["content"] = stats(*slot_view(buf, header)) if not args.watch else None

        if args.watch:
            samples = []
            deadline = time.time() + args.watch
            last_id = None
            while time.time() < deadline:
                header = read_header(buf)
                data, w, h, stride = slot_view(buf, header)
                changed = None
                if last_id is not None and header["frameId"] != last_id:
                    pass
                samples.append((time.time(), header["frameId"], bytes(data[:0])))
                last_id = header["frameId"]
                time.sleep(0.2)
            first, last = samples[0], samples[-1]
            span = max(1e-6, last[0] - first[0])
            header["watch"] = {
                "samples": len(samples),
                "framesAdvanced": last[1] - first[1],
                "fps": round((last[1] - first[1]) / span, 1),
                "finalFrameId": last[1],
            }
        print(json.dumps(header, indent=2, default=str) if args.json else json.dumps(header, default=str))
    finally:
        buf.close()
        os.close(fd)


if __name__ == "__main__":
    main()
