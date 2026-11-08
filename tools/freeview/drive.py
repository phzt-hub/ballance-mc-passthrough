#!/usr/bin/env python3
"""Drive one free-view acceptance run and record what the camera channel saw.

  drive.py --out /tmp/freeview/control.csv --mode control
  drive.py --out /tmp/freeview/orbit.csv   --mode orbit --shots /tmp/freeview

Control: hold Up and let the ball roll. Orbit: press F, turn the view 90
degrees right, then 17 degrees up, press F again, then hold Up exactly the same
way. Both runs print the ball's heading, which is the measurement that says the
free view is a viewpoint and not a new way of steering.

The samples come straight out of the shared camera object (MpCamera.h), i.e.
the same numbers the Minecraft client is driven with -- not the Ballance log.
"""
import argparse
import math
import os
import struct
import subprocess
import sys
import time

LAYOUT = "<IIIIII" + "3f" * 3 + "4f" + "3f" + "f" + "I" + "Q"
YAW_PIXELS = 628    # 0.0025 rad/px * 628 = 90 degrees at the default sensitivity
PITCH_PIXELS = 120  # 0.3 rad = 17 degrees


def read_once(fd):
    for _ in range(64):
        os.lseek(fd, 0, os.SEEK_SET)
        raw = os.read(fd, 4096)
        before = struct.unpack_from("<I", raw, 8)[0]
        value = struct.unpack_from(LAYOUT, raw, 0)
        after = struct.unpack_from("<I", raw, 8)[0]
        if before == after and before:
            return value
    raise SystemExit("the camera channel never settled")


def sample(fd, rows, secs, tag, clock):
    end = time.time() + secs
    while time.time() < end:
        v = read_once(fd)
        rows.append((time.time() - clock, tag,
                     v[19], v[20], v[21],      # ball
                     v[6], v[7], v[8],         # eye
                     v[9], v[10], v[11],       # forward
                     v[15]))                   # fov (vertical, radians)
        time.sleep(0.04)


def move(dx, dy):
    steps = max(1, max(abs(dx), abs(dy)) // 20)
    for _ in range(steps):
        subprocess.run(["xdotool", "mousemove_relative", "--",
                        str(int(dx / steps)), str(int(dy / steps))], check=False)
        time.sleep(0.04)


class Shooter:
    """Grabs the game window, so a shot is the picture and not a claim."""

    def __init__(self, directory):
        self.dir = directory
        self.geometry = None
        if not directory:
            return
        win = subprocess.run(["xdotool", "search", "--name", "Ballance"],
                             capture_output=True, text=True).stdout.split()
        if win:
            info = subprocess.run(["xwininfo", "-id", win[-1]],
                                  capture_output=True, text=True).stdout
            keys = {}
            for line in info.splitlines():
                if "Absolute upper-left X" in line:
                    keys["x"] = line.split()[-1]
                elif "Absolute upper-left Y" in line:
                    keys["y"] = line.split()[-1]
                elif line.strip().startswith("Width:"):
                    keys["w"] = line.split()[-1]
                elif line.strip().startswith("Height:"):
                    keys["h"] = line.split()[-1]
            if len(keys) == 4:
                self.geometry = (keys["x"], keys["y"], keys["w"], keys["h"])

    def __call__(self, name):
        if not self.geometry:
            return
        x, y, w, h = self.geometry
        subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "x11grab",
                        "-video_size", "%sx%s" % (w, h), "-i", "%s+%s,%s" % (
                            os.environ.get("DISPLAY", ":99"), x, y),
                        "-frames:v", "1", "-y", os.path.join(self.dir, name + ".png")],
                       check=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--mode", choices=["control", "orbit"], required=True)
    ap.add_argument("--shots", default="")
    ap.add_argument("--hold", type=float, default=1.5)
    args = ap.parse_args()

    fd = os.open("/dev/shm/ballance_mc_camera", os.O_RDONLY)
    rows = []
    clock = time.time()
    shot = Shooter(args.shots)

    sample(fd, rows, 0.8, "start", clock)
    if args.shots:
        shot("01-level")
    if args.mode == "orbit":
        subprocess.run(["xdotool", "key", "f"], check=False)
        sample(fd, rows, 1.0, "on", clock)
        if args.shots:
            shot("02-freeview-on")
        move(YAW_PIXELS, 0)
        sample(fd, rows, 1.0, "yaw", clock)
        if args.shots:
            shot("03-yaw")
        move(0, -PITCH_PIXELS)
        sample(fd, rows, 1.0, "pitch", clock)
        if args.shots:
            shot("04-pitch")
        subprocess.run(["xdotool", "key", "f"], check=False)
        sample(fd, rows, 1.2, "off", clock)
        if args.shots:
            shot("05-back")

    subprocess.run(["xdotool", "keydown", "Up"], check=False)
    sample(fd, rows, args.hold, "roll", clock)
    subprocess.run(["xdotool", "keyup", "Up"], check=False)
    sample(fd, rows, 2.0, "after", clock)
    if args.shots:
        shot("06-rolled")

    with open(args.out, "w") as f:
        f.write("t,tag,bx,by,bz,ex,ey,ez,fx,fy,fz,fovy\n")
        for r in rows:
            f.write("%.3f,%s,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.5f,%.5f,%.5f,%.5f\n" % r)

    start = [r for r in rows if r[1] == "start"][-1]
    end = [r for r in rows if r[1] in ("after", "roll")][-1]
    dx = end[2] - start[2]
    dz = end[4] - start[4]
    print("%s: ball dx=%.2f dz=%.2f heading=%.1f deg" %
          (args.mode, dx, dz, math.degrees(math.atan2(dx, dz))))


main()
