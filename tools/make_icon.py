"""Generates the Needle icon as 32x32 pixel art, in the Ghost Mason / Ortho / Gauge icon style.

Style: dark rounded-square badge (#10161C) with 4-px stepped corners, soft radial glow, a block
character with a cute face, cream 4-point sparkles. Subject: a wooden jukebox cube with a music disc
(Last.fm-red label) lying on top, a tonearm resting its needle on the record, and a music note.

Usage: python tools/make_icon.py  -> media/icon.png (512), media/icon-128.png (128),
                                     src/main/resources/assets/needle/icon.png (128)
"""
import math
import os
import shutil
import sys

from PIL import Image

N = 32
BG = (16, 22, 28, 255)
GLOW = (58, 38, 24)
TOP = (200, 146, 96, 255)
RIM = (236, 196, 146, 255)
LEFT = (160, 104, 62, 255)
RIGHT = (116, 72, 42, 255)
EDGE = (78, 46, 26, 255)
EYE = (40, 20, 10, 255)
EYE_HI = (255, 236, 220, 255)
DISC = (26, 26, 32, 255)
GROOVE = (70, 70, 84, 255)
SHINE = (150, 150, 168, 255)
LABEL = (213, 16, 7, 255)  # Last.fm red
SPINDLE = (255, 236, 200, 255)
ARM = (196, 200, 210, 255)
ARM_DARK = (120, 124, 136, 255)
NOTE = (255, 236, 140, 255)
SPARK = (255, 236, 140)


def blend(dst, src):
    """Alpha-composite src (r,g,b,a) over dst (r,g,b,a)."""
    sa = src[3] / 255
    da = dst[3] / 255
    oa = sa + da * (1 - sa)
    if oa == 0:
        return (0, 0, 0, 0)
    c = tuple(round((src[i] * sa + dst[i] * da * (1 - sa)) / oa) for i in range(3))
    return c + (round(oa * 255),)


def corner_cut(x, y):
    """Stepped rounded corners: 4,3,2,1 transparent pixels on rows 0..3 (mirrored)."""
    steps = [4, 3, 2, 1]
    for yy, xx in ((y, x), (N - 1 - y, x), (y, N - 1 - x), (N - 1 - y, N - 1 - x)):
        if yy < 4 and xx < steps[yy]:
            return True
    return False


def main(out_dir, asset):
    px = [[(0, 0, 0, 0)] * N for _ in range(N)]

    def put(x, y, c):
        if 0 <= x < N and 0 <= y < N and not corner_cut(x, y):
            px[y][x] = blend(px[y][x], c) if c[3] < 255 else c

    for y in range(N):
        for x in range(N):
            if corner_cut(x, y):
                continue
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 16) / 15
            t = max(0.0, 1 - d) ** 1.6
            px[y][x] = tuple(round(BG[i] + (GLOW[i] - BG[i]) * t) for i in range(3)) + (255,)

    # jukebox cube with a face, seen a little more from above than true isometric so the lid has
    # room for the record (slope K instead of 1/2)
    xc, yc, W, H, F = 16, 15, 9, 6, 7
    K = H / W
    for y in range(N):
        for x in range(N):
            fx, fy = x + 0.5, y + 0.5
            if abs(fx - xc) / W + abs(fy - yc) / H <= 1.0:
                put(x, y, TOP)
                continue
            if xc - W <= fx < xc:
                te = yc + (fx - (xc - W)) * K
                if te <= fy < te + F:
                    put(x, y, LEFT)
            elif xc <= fx < xc + W:
                te = yc + ((xc + W) - fx) * K
                if te <= fy < te + F:
                    put(x, y, RIGHT)
    for x in range(xc - W, xc + W):
        fx = x + 0.5
        top = yc + ((fx - (xc - W)) if fx < xc else ((xc + W) - fx)) * K
        put(x, math.floor(top - 0.01), RIM)
    # dark outline along the back edges, so the top reads as a flat lid and not a bowl
    for x in range(xc - W, xc + W):
        fx = x + 0.5
        back = yc - H * (1 - abs(fx - xc) / W)
        put(x, math.ceil(back - 0.5) - 1, EDGE)
    for y in range(int(yc + H), int(yc + H + F)):
        put(16, y, EDGE)
    for ex, ey in ((11, 20), (13, 21)):
        for dy in range(3):
            put(ex, ey + dy, EYE)
        put(ex, ey, EYE_HI)

    # music disc lying on the lid, with a ring of wood around it
    dx, dy, rx, ry = 16, 15, 5.6, 3.4
    for y in range(N):
        for x in range(N):
            ex = (x + 0.5 - dx) / rx
            ey = (y + 0.5 - dy) / ry
            e = math.hypot(ex, ey)
            if e <= 1.0:
                c = DISC
                if 0.58 <= e <= 0.74:
                    c = GROOVE
                if e < 0.36:
                    c = LABEL
                put(x, y, c)
    put(12, 13, SHINE)  # light catching the record
    put(13, 12, SHINE)
    put(15, 15, SPINDLE)
    put(16, 15, SPINDLE)

    # tonearm: hub on a post at the right of the lid, arm down to the needle on the groove
    for y in (12, 13, 14):
        put(24, y, ARM_DARK)
    put(23, 10, ARM)
    put(24, 10, ARM)
    put(23, 11, ARM_DARK)
    put(24, 11, ARM_DARK)
    for x, y in ((22, 11), (21, 12), (20, 13)):
        put(x, y, ARM)
    put(19, 14, ARM_DARK)  # needle head on the groove

    # eighth note, top left
    for y in range(5, 10):
        put(9, y, NOTE)
    put(10, 5, NOTE)
    put(11, 6, NOTE)
    put(11, 7, NOTE)
    for x, y in ((7, 9), (8, 9), (7, 10), (8, 10), (9, 10)):
        put(x, y, NOTE)

    def sparkle(x, y, strong=True):
        put(x, y, SPARK + (255,))
        a = 200 if strong else 140
        for ddx, ddy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(x + ddx, y + ddy, SPARK + (a,))

    sparkle(25, 5, strong=False)
    sparkle(4, 27, strong=False)
    sparkle(27, 27)

    img = Image.new("RGBA", (N, N))
    img.putdata([c for row in px for c in row])
    os.makedirs(out_dir, exist_ok=True)
    img.resize((512, 512), Image.NEAREST).save(os.path.join(out_dir, "icon.png"))
    img.resize((128, 128), Image.NEAREST).save(os.path.join(out_dir, "icon-128.png"))
    os.makedirs(os.path.dirname(asset), exist_ok=True)
    shutil.copyfile(os.path.join(out_dir, "icon-128.png"), asset)
    print("wrote", out_dir, "and", asset)


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "media"),
         os.path.join(root, "src", "main", "resources", "assets", "needle", "icon.png"))
