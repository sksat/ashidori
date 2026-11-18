#!/usr/bin/env python3
"""Checks extracted region packs (build.sh) and measures them.

  verify.py pack <archive.pmtiles> <cell> <minzoom> <maxzoom>
      Prints one JSON object: size, tiles and bytes per zoom, the header's
      bounds and zooms, and the glyph ranges its label strings need (the
      rules of tools/basemap/assemble.py). Exits 1 if the archive is not
      PMTiles v3, its zooms differ, its header bounds leave the cell, or any
      tile lies outside the cell.
  verify.py borders <archive.pmtiles>...
      Exits 1 if any tile (z/x/y) is in more than one archive: neighbouring
      packs must not overlap at their borders.

Standard library only.
"""
import json
import os
import struct
import sys
from collections import Counter

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, "..", "basemap"))
sys.path.insert(0, here)
import assemble  # noqa: E402
import cells  # noqa: E402


def zxy(tile_id):
    """PMTiles v3 tile id -> (z, x, y) (Hilbert order per zoom)."""
    z = 0
    acc = 0
    while True:
        n = 1 << (2 * z)
        if tile_id < acc + n:
            break
        acc += n
        z += 1
    t = tile_id - acc
    side = 1 << z
    x = y = 0
    s = 1
    while s < side:
        rx = 1 & (t // 2)
        ry = 1 & (t ^ rx)
        if ry == 0:
            if rx == 1:
                x = s - 1 - x
                y = s - 1 - y
            x, y = y, x
        x += s * rx
        y += s * ry
        t //= 4
        s *= 2
    return z, x, y


class Pack(assemble.PMTiles):
    def __init__(self, path):
        super().__init__(path)
        self.f.seek(0)
        h = self.f.read(127)
        self.bounds = [v / 1e7 for v in struct.unpack_from("<4i", h, 102)]
        (self.n_addressed,) = struct.unpack_from("<Q", h, 72)

    def entries(self):
        stack = [(self.root_off, self.root_len)]
        while stack:
            off, n = stack.pop()
            for tid, run, length, o in self.directory(off, n):
                if run == 0:
                    stack.append((self.leaf_off + o, length))
                else:
                    yield tid, run, length, o

    def addressed(self):
        for tid, run, _, _ in self.entries():
            for k in range(run):
                yield zxy(tid + k)


def check_pack(path, cell, minzoom, maxzoom):
    cx, cy = cells.parse(cell)
    p = Pack(path)
    problems = []
    if (p.minzoom, p.maxzoom) != (minzoom, maxzoom):
        problems.append(f"zoom {p.minzoom}-{p.maxzoom}, want {minzoom}-{maxzoom}")
    w, s, e, n = cells.bbox(cx, cy)
    bw, bs, be, bn = p.bounds
    # E7 fixed point: allow its rounding.
    eps = 2e-7
    if not (bw >= w - eps and bs >= s - eps and be <= e + eps and bn <= n + eps):
        problems.append(f"header bounds {p.bounds} leave the cell {[w, s, e, n]}")
    tiles = Counter()
    outside = 0
    for z, x, y in p.addressed():
        tiles[z] += 1
        if z < cells.Z or (x >> (z - cells.Z), y >> (z - cells.Z)) != (cx, cy):
            outside += 1
    if outside:
        problems.append(f"{outside} tiles outside the cell")
    per_zoom_bytes = Counter()
    seen = set()
    for tid, _, length, o in p.entries():
        if o not in seen:
            seen.add(o)
            per_zoom_bytes[zxy(tid)[0]] += length
    ranges = {0}
    for t in p.tiles():
        for st in assemble.tile_strings(t):
            for ch in st:
                cp = ord(ch)
                if cp <= 0xFFFF and not assemble.is_local(cp):
                    ranges.add(cp // 256)
    return {
        "cell": cell,
        "file": os.path.basename(path),
        "bytes": os.path.getsize(path),
        "minzoom": p.minzoom,
        "maxzoom": p.maxzoom,
        "bounds": p.bounds,
        "tiles": sum(tiles.values()),
        "contents": len(seen),
        "tiles_per_zoom": {z: tiles[z] for z in sorted(tiles)},
        "bytes_per_zoom": {z: per_zoom_bytes[z] for z in sorted(per_zoom_bytes)},
        "glyph_ranges": sorted(ranges),
        "problems": problems,
    }


def main(a):
    if len(a) >= 5 and a[0] == "pack":
        r = check_pack(a[1], a[2], int(a[3]), int(a[4]))
        print(json.dumps(r))
        sys.exit(1 if r["problems"] else 0)
    if len(a) >= 2 and a[0] == "borders":
        owner = {}
        shared = 0
        for path in a[1:]:
            for t in Pack(path).addressed():
                if t in owner and owner[t] != path:
                    shared += 1
                    if shared <= 5:
                        print(f"tile {t} in {owner[t]} and {path}", file=sys.stderr)
                owner[t] = path
        print(json.dumps({"archives": len(a) - 1, "tiles": len(owner), "shared": shared}))
        sys.exit(1 if shared else 0)
    sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
