#!/usr/bin/env python3
"""
Trim a tall one-shot screenshot.

capture.sh renders each screen onto a very tall virtual display (`wm size`) and
grabs it in a single `screencap` — so the whole page is captured at once with no
scrolling or stitching. The app's bottom bar (mini-player + nav) pins to the
bottom of that tall frame, leaving a band of empty background between the end of
the real content and the pinned bar on screens whose content doesn't fill the
height. This removes that gap (the single longest run of uniform background
rows), keeping the content and the bottom bar adjacent.

Also reports whether the page was TRUNCATED — if there's no meaningful gap, the
content filled the whole virtual display and capture.sh should retry taller.

Usage:  trim.py IN.png OUT.png
Exit 0 = trimmed (page fully captured). Exit 3 = truncated (capture taller).
Requires Pillow.
"""
import sys
from PIL import Image

SAMPLES = 48
UNIFORM_RANGE = 10      # a row is "solid" if sampled channels span <= this
MIN_GAP = 250           # ignore gaps shorter than this (inter-section spacing)


def main():
    inp, out = sys.argv[1], sys.argv[2]
    img = Image.open(inp).convert("RGB")
    w, h = img.size
    px = img.load()
    xs = [int(i * (w - 1) / (SAMPLES - 1)) for i in range(SAMPLES)]

    # Per row: is it a solid color, and what color.
    solid = [False] * h
    color = [None] * h
    for y in range(h):
        rmin = gmin = bmin = 255
        rmax = gmax = bmax = 0
        for x in xs:
            r, g, b = px[x, y]
            rmin, gmin, bmin = min(rmin, r), min(gmin, g), min(bmin, b)
            rmax, gmax, bmax = max(rmax, r), max(gmax, g), max(bmax, b)
        if (rmax - rmin) <= UNIFORM_RANGE and (gmax - gmin) <= UNIFORM_RANGE and (bmax - bmin) <= UNIFORM_RANGE:
            solid[y] = True
            color[y] = (rmin, gmin, bmin)

    # Background = the most common solid-row color (the page fill).
    from collections import Counter
    cnt = Counter(color[y] for y in range(h) if solid[y])
    if not cnt:
        img.save(out); print(f"{out}: no solid rows; {w}x{h}"); return 0
    bg = cnt.most_common(1)[0][0]

    def is_bg(y):
        return solid[y] and color[y] == bg

    # Longest run of background rows = the trailing gap to remove.
    best_start, best_len = -1, 0
    y = 0
    while y < h:
        if is_bg(y):
            s = y
            while y < h and is_bg(y):
                y += 1
            if (y - s) > best_len:
                best_start, best_len = s, y - s
        else:
            y += 1

    # Truncation check: content reaches the bottom bar with no real gap.
    if best_len < MIN_GAP:
        img.save(out)
        print(f"{out}: TRUNCATED (largest gap {best_len}px < {MIN_GAP}); {w}x{h}")
        return 3

    keep = [True] * h
    # Keep a small breath of background at the seam for visual padding.
    pad = 24
    for yy in range(best_start + pad, best_start + best_len):
        keep[yy] = False
    rows = [yy for yy in range(h) if keep[yy]]
    new_h = len(rows)
    canvas = Image.new("RGB", (w, new_h))
    # Block-copy contiguous kept runs.
    out_y = 0
    i = 0
    while i < len(rows):
        j = i + 1
        while j < len(rows) and rows[j] == rows[j - 1] + 1:
            j += 1
        y0, y1 = rows[i], rows[j - 1] + 1
        canvas.paste(img.crop((0, y0, w, y1)), (0, out_y))
        out_y += (y1 - y0)
        i = j
    canvas.save(out)
    print(f"{out}: trimmed {best_len}px gap -> {w}x{new_h}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
