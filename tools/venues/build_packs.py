#!/usr/bin/env python3
"""Builds offline venue packs from extract.py's venues.tsv and measures them
(prototype: the format is not read by the app yet).

  build_packs.py <venues.tsv> <out dir> [--grid z7|z8|deg1 ...] [--source NAME] [--checkin-only]

For each grid it writes <out dir>/<grid>/<pack>.aven (and .aven.zst when
zstd is available), plus <out dir>/japan.aven for the whole input, and
prints the counts and sizes. <out dir>/stats.json holds the same numbers.

Pack format "AVEN" version 1. All integers are unsigned LEB128 varints
("uv") or zigzag-encoded signed varints ("sv"); strings are UTF-8.

  "AVEN" u8 version(=1)
  uv scale                     coordinates are round(deg * scale), scale = 1e5
  sv south  sv west            pack origin, in scale units
  uv cell                      cell size in scale units (1000 = 0.01 deg, ~1 km)
  uv cellsLon                  cells per row; cell key = latIdx * cellsLon + lonIdx
  uv nCategories { uv len, "key=value" }*     most frequent first
  uv nNames { uv shared, uv len, suffix }*    sorted, deduplicated, front-coded
  uv nCells { uv keyDelta, uv count }*        non-empty cells, ascending key
  uv nPoints                                  points ordered by cell key, then lat, lon
  sv dLat[n]  sv dLon[n]       delta from the previous point (the origin for the first)
  uv category[n]
  uv name[n]                   index into names
  uv nameJa[n]                 0 = none or same as name, else index + 1
  uv osm[n]                    id * 4 + type (0 node, 1 way, 2 relation)

The whole file is then compressed with deflate (zlib, level 9; ".aven" is
the raw deflate stream wrapped in zlib) and, for comparison, zstd -19.
Standard library only (zstd needs Python 3.14's compression.zstd).
"""
import argparse
import collections
import json
import math
import os
import statistics
import sys
import time
import zlib

try:
    from compression import zstd
except ImportError:  # before Python 3.14
    zstd = None

SCALE = 100_000
CELL = 1_000  # 0.01 degree
TYPES = {"n": 0, "w": 1, "r": 2}


def uv(out, v):
    assert v >= 0
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def sv(out, v):
    uv(out, (v << 1) if v >= 0 else ((-v << 1) - 1))


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            t, i, lat, lon, k, v, name, ja = line.rstrip("\n").split("\t")
            rows.append((int(lat), int(lon), t, int(i), k, v, name, ja))
    return rows


def tile_z(lat_e5, lon_e5, z):
    lat = lat_e5 / SCALE
    lon = lon_e5 / SCALE
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    r = math.radians(lat)
    y = int((1.0 - math.asinh(math.tan(r)) / math.pi) / 2.0 * n)
    return f"{z}-{x}-{y}"


def tile_deg(lat_e5, lon_e5):
    return f"{math.floor(lat_e5 / SCALE)}-{math.floor(lon_e5 / SCALE)}"


# Not places one checks in to: track segments, crossings, platforms (also
# mapped as stops), parking, signboards. Dropped with --checkin-only.
NOT_VENUES = {
    "railway": None,  # every value except those in KEEP_RAILWAY
    "public_transport": None,  # every value except "station"
    "amenity": {"parking", "parking_space", "parking_entrance", "bicycle_parking", "motorcycle_parking",
                "bench", "waste_basket", "vending_machine", "toilets", "drinking_water", "shelter",
                "telephone", "post_box", "atm", "charging_station", "bicycle_rental", "recycling"},
    "tourism": {"information"},
    "leisure": {"pitch", "playground", "swimming_pool", "track", "slipway", "picnic_table"},
    "historic": {"memorial", "boundary_stone", "milestone", "wayside_shrine", "wayside_cross"},
}
KEEP = {"railway": {"station", "halt", "tram_stop"}, "public_transport": {"station"}}


def checkin_venue(r):
    k, v = r[4], r[5]
    if k in KEEP:
        return v in KEEP[k]
    drop = NOT_VENUES.get(k)
    return not (drop and v in drop)


def encode(rows, with_ids=True, sections=None):
    south = min(r[0] for r in rows)
    west = min(r[1] for r in rows)
    south -= south % CELL
    west -= west % CELL
    east = max(r[1] for r in rows)
    cells_lon = (east - west) // CELL + 1

    def key(r):
        return ((r[0] - south) // CELL) * cells_lon + (r[1] - west) // CELL

    rows = sorted(rows, key=lambda r: (key(r), r[0], r[1]))
    cats = collections.Counter(f"{r[4]}={r[5]}" for r in rows)
    cat_ids = {c: i for i, (c, _) in enumerate(cats.most_common())}
    names = sorted({r[6] for r in rows} | {r[7] for r in rows if r[7] and r[7] != r[6]},
                   key=lambda s: s.encode("utf-8"))
    name_ids = {s: i for i, s in enumerate(names)}

    out = bytearray(b"AVEN")
    out.append(1)
    uv(out, SCALE)
    sv(out, south)
    sv(out, west)
    uv(out, CELL)
    uv(out, cells_lon)
    uv(out, len(cat_ids))
    for c, _ in cats.most_common():
        b = c.encode("utf-8")
        uv(out, len(b))
        out += b
    uv(out, len(names))
    prev = b""
    for s in names:
        b = s.encode("utf-8")
        shared = 0
        while shared < min(len(b), len(prev)) and b[shared] == prev[shared]:
            shared += 1
        uv(out, shared)
        uv(out, len(b) - shared)
        out += b[shared:]
        prev = b
    cells = collections.Counter(key(r) for r in rows)
    uv(out, len(cells))
    last = 0
    for k in sorted(cells):
        uv(out, k - last)
        uv(out, cells[k])
        last = k
    mark = {}
    mark["header+categories+names+cells"] = len(out)
    uv(out, len(rows))
    plat, plon = south, west
    for r in rows:
        sv(out, r[0] - plat)
        plat = r[0]
    for r in rows:
        sv(out, r[1] - plon)
        plon = r[1]
    mark["coordinates"] = len(out)
    for r in rows:
        uv(out, cat_ids[f"{r[4]}={r[5]}"])
    mark["categories"] = len(out)
    for r in rows:
        uv(out, name_ids[r[6]])
    for r in rows:
        uv(out, name_ids[r[7]] + 1 if r[7] and r[7] != r[6] else 0)
    mark["name refs"] = len(out)
    if with_ids:
        for r in rows:
            uv(out, r[3] * 4 + TYPES[r[2]])
    mark["osm ids"] = len(out)
    if sections is not None:
        # Each section alone, raw and deflated (names: the string table).
        prev = 0
        for k, end in mark.items():
            part = bytes(out[prev:end])
            sections[k] = {"raw": len(part), "deflate": len(zlib.compress(part, 9))}
            prev = end
    return bytes(out)


def sizes(raw):
    s = {"raw": len(raw), "deflate": len(zlib.compress(raw, 9))}
    if zstd is not None:
        s["zstd19"] = len(zstd.compress(raw, level=19))
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tsv")
    ap.add_argument("out")
    ap.add_argument("--grid", action="append", default=None)
    ap.add_argument("--source", default="")
    ap.add_argument("--checkin-only", action="store_true", help="drop features that are not places to check in to")
    a = ap.parse_args()
    grids = a.grid or ["z7", "z8", "deg1"]
    t = time.time()
    rows = load(a.tsv)
    if a.checkin_only:
        rows = [r for r in rows if checkin_venue(r)]
    stats = {"source": a.source, "features": len(rows)}
    stats["by_type"] = dict(collections.Counter(r[2] for r in rows))
    stats["by_key"] = dict(collections.Counter(r[4] for r in rows).most_common())
    stats["top_values"] = [f"{k}={v}: {n}" for (k, v), n in
                           collections.Counter((r[4], r[5]) for r in rows).most_common(25)]
    stats["with_name_ja"] = sum(1 for r in rows if r[7])
    stats["distinct_names"] = len({r[6] for r in rows})
    os.makedirs(a.out, exist_ok=True)
    sections = {}
    whole = encode(rows, sections=sections)
    stats["japan_sections"] = sections
    with open(os.path.join(a.out, "japan.aven"), "wb") as f:
        f.write(zlib.compress(whole, 9))
    stats["japan"] = sizes(whole)
    stats["japan_without_ids"] = sizes(encode(rows, with_ids=False))
    for g in grids:
        if g == "deg1":
            key = lambda r: tile_deg(r[0], r[1])
        else:
            z = int(g[1:])
            key = lambda r, z=z: tile_z(r[0], r[1], z)
        packs = collections.defaultdict(list)
        for r in rows:
            packs[key(r)].append(r)
        d = os.path.join(a.out, g)
        os.makedirs(d, exist_ok=True)
        per = {}
        for name, prs in sorted(packs.items()):
            raw = encode(prs)
            with open(os.path.join(d, name + ".aven"), "wb") as f:
                f.write(zlib.compress(raw, 9))
            if zstd is not None:
                with open(os.path.join(d, name + ".aven.zst"), "wb") as f:
                    f.write(zstd.compress(raw, level=19))
            per[name] = {"features": len(prs), **sizes(raw)}
        defl = [p["deflate"] for p in per.values()]
        stats[g] = {
            "packs": len(per),
            "total": {k: sum(p[k] for p in per.values()) for k in next(iter(per.values())) if k != "features"},
            "deflate_min": min(defl), "deflate_median": int(statistics.median(defl)), "deflate_max": max(defl),
            "largest": sorted(((p["deflate"], n, p["features"]) for n, p in per.items()), reverse=True)[:5],
            "per_pack": per,
        }
    stats["seconds"] = round(time.time() - t)
    with open(os.path.join(a.out, "stats.json"), "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=1)
    summary = {k: v for k, v in stats.items() if k not in grids}
    for g in grids:
        summary[g] = {k: v for k, v in stats[g].items() if k != "per_pack"}
    json.dump(summary, sys.stdout, ensure_ascii=False, indent=1)
    print()


if __name__ == "__main__":
    main()
