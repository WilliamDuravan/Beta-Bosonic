#!/usr/bin/env python3
"""
Tools for BosonDiag raw recordings (<name>.y16 + <name>.csv).

  python3 y16_tools.py info    REC.y16            # sizes, timing, dropped-frame report
  python3 y16_tools.py mkv     REC.y16 [-o out.mkv]   # lossless FFV1 16-bit video (needs ffmpeg)
  python3 y16_tools.py preview REC.y16 [-o out.mp4]   # tone-mapped viewable MP4 (needs ffmpeg + numpy)
  python3 y16_tools.py tiff    REC.y16 --start 0 --count 10 [--outdir frames]   # 16-bit TIFFs (needs numpy)

The .csv next to the .y16 (same base name) is read automatically; width/height are also parsed
from the filename if the csv is missing.
"""
import argparse
import os
import re
import statistics
import struct
import subprocess
import sys


def read_meta(y16_path):
    base = os.path.splitext(y16_path)[0]
    meta, rows = {}, []
    csv_path = base + ".csv"
    m = re.search(r"_(\d+)x(\d+)_gray16le_(\d+)fps", os.path.basename(y16_path))
    if m:
        meta["width"], meta["height"], meta["nominal_fps"] = m.group(1), m.group(2), m.group(3)
    if os.path.exists(csv_path):
        with open(csv_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("#"):
                    for k, v in re.findall(r"(\w+)=([^\s,]+)", line):
                        meta[k] = v
                elif line and not line.startswith("file_index"):
                    a = line.split(",")
                    rows.append((int(a[0]), int(a[1]), int(a[2]), int(a[3])))
    else:
        print("note: no .csv found next to the .y16; using filename for dimensions", file=sys.stderr)
    if "width" not in meta:
        sys.exit("cannot determine width/height (need the .csv or the original filename)")
    meta["width"], meta["height"] = int(meta["width"]), int(meta["height"])
    meta["nominal_fps"] = int(meta.get("nominal_fps", 60))
    meta["frame_bytes"] = meta["width"] * meta["height"] * 2
    return meta, rows


def cmd_info(a):
    meta, rows = read_meta(a.file)
    fb = meta["frame_bytes"]
    size = os.path.getsize(a.file)
    n = size // fb
    print(f"file: {a.file}")
    print(f"size: {size/1e6:.1f} MB = {n} frames of {meta['width']}x{meta['height']} gray16le"
          + (f"  (+{size % fb} stray bytes!)" if size % fb else ""))
    print(f"nominal fps: {meta['nominal_fps']}   camera serial: {meta.get('camera_serial','?')}")
    if not rows:
        return
    if len(rows) != n:
        print(f"WARNING: csv has {len(rows)} rows but file holds {n} frames")
    t = [r[2] for r in rows]
    if len(t) > 1 and t[-1] > t[0]:
        dur = (t[-1] - t[0]) / 1e6
        print(f"duration: {dur:.2f} s   measured rate: {(len(t)-1)/dur:.2f} fps")
    usb = [r[1] for r in rows]
    lost = sum(max(0, b - a_ - 1) for a_, b in zip(usb, usb[1:]))
    print(f"frames lost inside the app (usb_frame gaps): {lost}")
    pts = [r[3] for r in rows if r[3] >= 0]
    if len(pts) > 2:
        d = [(b - a_) % (1 << 32) for a_, b in zip(pts, pts[1:])]
        med = statistics.median(d)
        gaps = sum(max(0, round(x / med) - 1) for x in d if med > 0 and x > 1.5 * med)
        print(f"camera timestamp step (median): {med:.0f} ticks; frames missing per camera timestamps: {gaps}")
    if "stopped" in meta:
        print(f"recording stopped early: {meta['stopped']}")


def cmd_mkv(a):
    meta, _ = read_meta(a.file)
    out = a.output or os.path.splitext(a.file)[0] + ".mkv"
    cmd = ["ffmpeg", "-y", "-f", "rawvideo", "-pixel_format", "gray16le",
           "-video_size", f"{meta['width']}x{meta['height']}", "-framerate", str(meta["nominal_fps"]),
           "-i", a.file, "-c:v", "ffv1", out]
    print(" ".join(cmd))
    subprocess.run(cmd, check=True)


def cmd_preview(a):
    import numpy as np
    meta, _ = read_meta(a.file)
    w, h, fb = meta["width"], meta["height"], meta["frame_bytes"]
    out = a.output or os.path.splitext(a.file)[0] + "_preview.mp4"
    cmd = ["ffmpeg", "-y", "-f", "rawvideo", "-pixel_format", "gray", "-video_size", f"{w}x{h}",
           "-framerate", str(meta["nominal_fps"]), "-i", "-", "-vf", "scale=iw*2:ih*2:flags=neighbor",
           "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", out]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    lo = hi = None
    with open(a.file, "rb") as f:
        while True:
            buf = f.read(fb)
            if len(buf) < fb:
                break
            fr = np.frombuffer(buf, dtype="<u2").astype(np.float32)
            l, hh = np.percentile(fr, [1, 99])
            lo = l if lo is None else lo + 0.1 * (l - lo)
            hi = hh if hi is None else hi + 0.1 * (hh - hi)
            rng = max(hi - lo, a.min_range)
            base = (lo + hi) / 2 - rng / 2 if (hi - lo) < a.min_range else lo
            g = np.clip((fr - base) * (255.0 / rng), 0, 255).astype(np.uint8)
            p.stdin.write(g.tobytes())
    p.stdin.close()
    p.wait()
    print("wrote", out)


def tiff16(raw, w, h):
    entries = 9
    data_off = 8 + 2 + entries * 12 + 4
    def se(tag, v): return struct.pack("<HHIHH", tag, 3, 1, v, 0)
    def le(tag, v): return struct.pack("<HHII", tag, 4, 1, v)
    ifd = (se(256, w) + se(257, h) + se(258, 16) + se(259, 1) + se(262, 1) +
           le(273, data_off) + se(277, 1) + se(278, h) + le(279, len(raw)))
    return b"II" + struct.pack("<HI", 42, 8) + struct.pack("<H", entries) + ifd + struct.pack("<I", 0) + raw


def cmd_tiff(a):
    meta, _ = read_meta(a.file)
    w, h, fb = meta["width"], meta["height"], meta["frame_bytes"]
    os.makedirs(a.outdir, exist_ok=True)
    with open(a.file, "rb") as f:
        f.seek(a.start * fb)
        for i in range(a.count):
            buf = f.read(fb)
            if len(buf) < fb:
                break
            path = os.path.join(a.outdir, f"frame_{a.start + i:06d}.tif")
            with open(path, "wb") as o:
                o.write(tiff16(buf, w, h))
    print("wrote TIFFs to", a.outdir)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, fn in (("info", cmd_info), ("mkv", cmd_mkv), ("preview", cmd_preview), ("tiff", cmd_tiff)):
        sp = sub.add_parser(name)
        sp.add_argument("file")
        sp.set_defaults(fn=fn)
        if name in ("mkv", "preview"):
            sp.add_argument("-o", "--output")
        if name == "preview":
            sp.add_argument("--min-range", type=float, default=150.0)
        if name == "tiff":
            sp.add_argument("--start", type=int, default=0)
            sp.add_argument("--count", type=int, default=1)
            sp.add_argument("--outdir", default="frames")
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
