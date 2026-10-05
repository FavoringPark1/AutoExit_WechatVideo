#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""纯标准库生成应用图标（「跳过」符号 + 圆角蓝底），不依赖 Pillow。

刻意做成一个很普通的"跳过/快进"图标，和「跳广告」这个名称一致，
避免在桌面上暴露真实用途。
"""
import math
import os
import struct
import sys
import zlib


def write_png(path, size, rows):
    raw = b"".join(b"\x00" + bytes(v for px in row for v in px) for row in rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    blob = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(blob)


def inside_round_rect(x, y, w, rad):
    dx = max(rad - x, 0.0, x - (w - rad))
    dy = max(rad - y, 0.0, y - (w - rad))
    return dx * dx + dy * dy <= rad * rad


def in_triangle(x, y, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])
    d1 = sign((x, y), a, b)
    d2 = sign((x, y), b, c)
    d3 = sign((x, y), c, a)
    neg = (d1 < 0) or (d2 < 0) or (d3 < 0)
    pos = (d1 > 0) or (d2 > 0) or (d3 > 0)
    return not (neg and pos)


def make(size, ss=3):
    w = float(size * ss)
    rad = w * 0.215

    bg = (0x1E, 0x6F, 0xE0)
    fg = (0xFF, 0xFF, 0xFF)

    # 「跳过」图形：一个向右的三角 + 一根竖条
    tri = ((0.325 * w, 0.300 * w), (0.325 * w, 0.700 * w), (0.615 * w, 0.500 * w))
    bar_x0, bar_x1 = 0.655 * w, 0.720 * w
    bar_y0, bar_y1 = 0.300 * w, 0.700 * w

    rows = []
    n = ss * ss
    for py in range(size):
        row = []
        for px in range(size):
            cr = cg = cb = 0
            covered = 0
            for sy in range(ss):
                for sx in range(ss):
                    x = px * ss + sx + 0.5
                    y = py * ss + sy + 0.5
                    if not inside_round_rect(x, y, w, rad):
                        continue
                    covered += 1
                    white = in_triangle(x, y, *tri) or (
                        bar_x0 <= x <= bar_x1 and bar_y0 <= y <= bar_y1)
                    c = fg if white else bg
                    cr += c[0]
                    cg += c[1]
                    cb += c[2]
            if covered == 0:
                row.append((0, 0, 0, 0))
            else:
                row.append((cr // covered, cg // covered, cb // covered,
                            (255 * covered) // n))
        rows.append(row)
    return rows


def main():
    app = sys.argv[1] if len(sys.argv) > 1 else "."
    targets = [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]
    for folder, size in targets:
        d = os.path.join(app, "res", folder)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "ic_launcher.png")
        write_png(p, size, make(size))
        print("wrote %s (%dx%d)" % (p, size, size))


if __name__ == "__main__":
    main()
