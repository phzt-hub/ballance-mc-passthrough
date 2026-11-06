#!/usr/bin/env python3
"""在邮箱里数 alpha：虚空世界那一帧到底透不透明。

M2a 的验收就是这一个数字——**没有人放方块时，画面里 alpha=0 的像素应该接近 100%**。
本工具直接读 mpframe.h 的共享内存，不经过任何一端，所以「MC 真的把透明背景送出来了」
这件事是独立可查的。

    ./void-frame.py                       # 数一遍，打印 alpha 直方图
    ./void-frame.py --png /tmp/v.png      # 另存 RGBA PNG（带 alpha）
    ./void-frame.py --preview /tmp/p.png  # 把 alpha 画成棋盘格，肉眼看得见哪里是空的
    ./void-frame.py --json
"""
import argparse
import json
import mmap
import os
import struct

HEADER = 4096
MAGIC = 0x3050464D


def open_mailbox(name):
    path = name if name.startswith("/") else "/dev/shm/" + name
    fd = os.open(path, os.O_RDONLY)
    size = os.fstat(fd).st_size
    if size < HEADER:
        raise SystemExit(f"{path}: only {size} bytes, no header")
    return fd, mmap.mmap(fd, size, mmap.MAP_SHARED, mmap.PROT_READ), path


def read_header(buf):
    (magic, version, width, height, stride, fmt, slot_bytes, slots,
     frame_id, pub_slot, pid, flags, ts) = struct.unpack_from("<13I", buf, 0)
    offsets = struct.unpack_from("<3Q", buf, 56)
    return {
        "magic_ok": magic == MAGIC, "width": width, "height": height,
        "stride": stride, "slotBytes": slot_bytes, "slotCount": slots,
        "frameId": frame_id, "publishedSlot": pub_slot, "producerPid": pid,
        "slotOffsets": offsets,
    }


def slot_bytes(buf, header):
    slot = header["publishedSlot"]
    off = header["slotOffsets"][slot]
    return bytes(buf[off:off + header["slotBytes"]]), header["width"], header["height"], header["stride"]


def alpha_histogram(data, width, height, stride):
    """BGRA 里的 A 是第 4 个字节。分四档：全透、接近全透、半透、不透明。"""
    buckets = {"a=0": 0, "0<a<64": 0, "64<=a<255": 0, "a=255": 0}
    total = width * height
    for y in range(height):
        row = data[y * stride:(y + 1) * stride]
        for x in range(width):
            a = row[x * 4 + 3]
            if a == 0:
                buckets["a=0"] += 1
            elif a < 64:
                buckets["0<a<64"] += 1
            elif a < 255:
                buckets["64<=a<255"] += 1
            else:
                buckets["a=255"] += 1
    return {k: {"pixels": v, "percent": round(100.0 * v / max(1, total), 2)} for k, v in buckets.items()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mailbox", default=os.environ.get("BALLANCE_MC_SHM", "ballance_mc_frame"))
    parser.add_argument("--png", default=None)
    parser.add_argument("--preview", default=None)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    fd, buf, path = open_mailbox(args.mailbox)
    try:
        header = read_header(buf)
        data, width, height, stride = slot_bytes(buf, header)
        result = {"mailbox": path, "width": width, "height": height,
                  "frameId": header["frameId"], "alpha": alpha_histogram(data, width, height, stride)}
        try:
            from PIL import Image
        except ImportError:
            Image = None
        if Image and args.png:
            Image.frombytes("RGBA", (width, height), data, "raw", "BGRA").save(args.png)
            result["png"] = args.png
        if Image and args.preview:
            # 棋盘格背景 + 原图，透明的地方能直接看出来
            image = Image.frombytes("RGBA", (width, height), data, "raw", "BGRA")
            board = Image.new("RGBA", (width, height))
            px = board.load()
            for y in range(height):
                for x in range(width):
                    v = 60 if ((x // 16) + (y // 16)) % 2 else 110
                    px[x, y] = (v, v, v, 255)
            board.alpha_composite(image)
            board.convert("RGB").save(args.preview)
            result["preview"] = args.preview
        print(json.dumps(result, indent=2) if args.json else json.dumps(result))
    finally:
        buf.close()
        os.close(fd)


if __name__ == "__main__":
    main()
