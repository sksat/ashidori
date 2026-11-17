#!/usr/bin/env python3
"""Inspects a local PMTiles v3 archive for the region-pack prototype.

  pmtiles_list.py <archive> [<z>-<x>-<y> cell]

Prints JSON: file size, header zooms and bounds, addressed tiles, directory
entries, distinct tile contents, tile bytes per zoom (each distinct content
counted once, at the first zoom that references it), and, when a cell is
given, the tiles outside that cell grouped by side.

Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
"""
import gzip
import json
import os
import struct
import sys
from collections import Counter, defaultdict


def varint(buf, i):
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, i
        shift += 7


def decompress(data, kind):
    if kind == 1:
        return data
    if kind == 2:
        return gzip.decompress(data)
    raise ValueError(f"unsupported compression {kind}")


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
    pos = tile_id - acc
    side = 1 << z
    x = y = 0
    s = 1
    t = pos
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


class Archive:
    def __init__(self, path):
        self.f = open(path, "rb")
        h = self.f.read(127)
        if h[:7] != b"PMTiles" or h[7] != 3:
            raise ValueError(f"{path}: not a PMTiles v3 archive")
        (self.root_off, self.root_len, self.meta_off, self.meta_len, self.leaf_off, self.leaf_len,
         self.data_off, self.data_len) = struct.unpack_from("<8Q", h, 8)
        (self.n_addressed, self.n_entries, self.n_contents) = struct.unpack_from("<3Q", h, 72)
        self.internal = h[97]
        self.minzoom, self.maxzoom = h[100], h[101]
        self.bounds = [v / 1e7 for v in struct.unpack_from("<4i", h, 102)]

    def read(self, off, n):
        self.f.seek(off)
        return self.f.read(n)

    def directory(self, off, n):
        buf = decompress(self.read(off, n), self.internal)
        count, i = varint(buf, 0)
        ids, runs, lens, offs = [], [], [], []
        last = 0
        for _ in range(count):
            d, i = varint(buf, i)
            last += d
            ids.append(last)
        for _ in range(count):
            r, i = varint(buf, i)
            runs.append(r)
        for _ in range(count):
            v, i = varint(buf, i)
            lens.append(v)
        for k in range(count):
            o, i = varint(buf, i)
            offs.append(offs[k - 1] + lens[k - 1] if o == 0 and k > 0 else o - 1)
        return list(zip(ids, runs, lens, offs))

    def entries(self):
        stack = [(self.root_off, self.root_len)]
        while stack:
            off, n = stack.pop()
            for tid, run, length, o in self.directory(off, n):
                if run == 0:
                    stack.append((self.leaf_off + o, length))
                else:
                    yield tid, run, length, o


def main(path, cell=None):
    a = Archive(path)
    seen = set()
    per_zoom_bytes = Counter()
    per_zoom_tiles = Counter()
    outside = defaultdict(Counter)
    entries = 0
    if cell:
        cz, cx, cy = map(int, cell.split("-"))
    for tid, run, length, off in a.entries():
        entries += 1
        for k in range(run):
            z, x, y = zxy(tid + k)
            per_zoom_tiles[z] += 1
            if k == 0 and off not in seen:
                seen.add(off)
                per_zoom_bytes[z] += length
            if cell and z >= cz:
                px, py = x >> (z - cz), y >> (z - cz)
                if (px, py) != (cx, cy):
                    side = ("W" if px < cx else "E" if px > cx else "") + ("N" if py < cy else "S" if py > cy else "")
                    outside[z][side] += 1
    out = {
        "file": os.path.basename(path),
        "bytes": os.path.getsize(path),
        "tile_data_bytes": a.data_len,
        "minzoom": a.minzoom,
        "maxzoom": a.maxzoom,
        "bounds": a.bounds,
        "addressed_tiles": sum(per_zoom_tiles.values()),
        "header_addressed": a.n_addressed,
        "entries": entries,
        "contents": len(seen),
        "tiles_per_zoom": {z: per_zoom_tiles[z] for z in sorted(per_zoom_tiles)},
        "bytes_per_zoom": {z: per_zoom_bytes[z] for z in sorted(per_zoom_bytes)},
    }
    if cell:
        out["outside_cell"] = {z: dict(c) for z, c in sorted(outside.items())}
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(*sys.argv[1:3])
