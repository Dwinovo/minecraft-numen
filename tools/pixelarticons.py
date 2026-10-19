"""把 pixelarticons 的 svg 转成界面用的小图标 png。

图标形状来自 pixelarticons(MIT,Copyright (c) 2019 Gerrit Halfmann,
https://github.com/halfmage/pixelarticons)——它本来就是按 12×12 的像素格画的,
只是以 2 倍(24×24)存成 svg。所以这里不做重采样:解析出那些轴对齐的多边形,
铺成 24 格网,再按 2×2 块原样折回 12 格,拿到的就是作者画的那张像素稿。

用法(需要先 `npm pack pixelarticons` 并解开):
    python pixelarticons.py <解开的 package 目录> <输出目录> copy=icon_copy reload=icon_refresh ...

输出:12×12、纯白、只有全透明与全不透明两档的 png——与 icon_send 那几枚同款,
颜色在运行时按控件状态着色(见 client.ui.mc.Sprites)。
"""
import os
import re
import struct
import sys
import zlib


def polys(path_d):
    """拆成一串闭合折线(全是轴对齐的)。子路径不一定是矩形——空心图形是
    一条轮廓走一圈,得按多边形填,不能取外包矩形。"""
    out = []
    last = (0, 0)
    for sub in re.split(r"[zZ]", path_d):
        sub = sub.strip()
        if not sub:
            continue
        m = re.match(r"([Mm])\s*(-?\d+)[\s,]*(-?\d+)(.*)", sub, re.S)
        if not m:
            sys.exit(f"看不懂的子路径: {sub!r}")
        dx, dy = int(m.group(2)), int(m.group(3))
        x = x0 = dx if m.group(1) == "M" else last[0] + dx
        y = y0 = dy if m.group(1) == "M" else last[1] + dy
        last = (x0, y0)
        pts = [(x, y)]
        for cmd, val in re.findall(r"([hvHV])\s*(-?\d+)", m.group(4)):
            val = int(val)
            if cmd == "h":
                x += val
            elif cmd == "H":
                x = val
            elif cmd == "v":
                y += val
            else:
                y = val
            pts.append((x, y))
        out.append(pts)
    return out


def inside(pts, px, py):
    """格心在不在这条闭合折线里(奇偶规则,射线朝右)。"""
    hits = 0
    n = len(pts)
    for i in range(n):
        (ax, ay), (bx, by) = pts[i], pts[(i + 1) % n]
        if ay != by and (ay > py) != (by > py) and px < ax:
            hits += 1
    return hits % 2 == 1


def grid12(svg_path):
    src = open(svg_path, encoding="utf-8").read()
    big = [[False] * 24 for _ in range(24)]
    for d in re.findall(r'<path[^>]*\sd="([^"]+)"', src):
        for pts in polys(d):
            for px in range(24):
                for py in range(24):
                    if inside(pts, px + 0.5, py + 0.5):
                        big[px][py] = True
    small = [[False] * 12 for _ in range(12)]
    for x in range(12):
        for y in range(12):
            block = [big[2 * x + dx][2 * y + dy] for dx in (0, 1) for dy in (0, 1)]
            small[x][y] = sum(block) >= 2
    return small


def write_png(grid, out_path):
    n = len(grid)
    raw = b""
    for y in range(n):
        raw += b"\x00"
        for x in range(n):
            raw += b"\xff\xff\xff\xff" if grid[x][y] else b"\x00\x00\x00\x00"

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    open(out_path, "wb").write(png)


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    pkg, out = sys.argv[1], sys.argv[2]
    for pair in sys.argv[3:]:
        src, dst = pair.split("=")
        grid = grid12(os.path.join(pkg, "svg", src + ".svg"))
        write_png(grid, os.path.join(out, dst + ".png"))
        print(f"{src}.svg -> {dst}.png")
        for y in range(12):
            print("  " + "".join("#" if grid[x][y] else "." for x in range(12)))


if __name__ == "__main__":
    main()
