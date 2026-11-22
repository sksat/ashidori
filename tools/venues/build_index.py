#!/usr/bin/env python3
"""Builds the offline venue index read by ashidori.core.VenueIndex from
extract.py's venues.tsv.

  build_index.py <venues.tsv> <out dir> [--source-file NAME --source-sha256 HEX
                 --source-date ISO8601 --tool TEXT ...] [--all] [--block-points N]

Writes <out dir>/venues.aven, manifest.json and ATTRIBUTION.

Selection: places one checks in to. Of extract.py's features (a name and one
of the app's category keys), it drops track segments, platforms, stop
positions, parking, signboards and street furniture ([[checkin_venue]]);
--all keeps everything (for measurements).

Format "AVEN" version 2: a grid of cells with an index, and the points in
deflate-compressed blocks, so that a reader inflates only the blocks near a
query. Integers are unsigned LEB128 varints ("uv") or zigzag-encoded signed
varints ("sv"); strings are UTF-8.

  "AVEN" u8 version(=2) u8 flags(=0) u32be headerLength
  header (headerLength bytes, not compressed):
    uv scale                   coordinates are round(deg * scale), scale = 1e5
    uv cell                    cell size in scale units (1000 = 0.01 deg, ~1 km)
    sv south  sv west          grid origin, in scale units
    uv rows  uv cols           grid size
    uv nCategories { uv len, "key=value" }*   most frequent first
    uv dictLength, dictionary  preset deflate dictionary of every block (may be
                               empty): frequent names, the most frequent last
    uv nBlocks { uv firstKeyDelta, uv points, uv compressedLength }*
  blocks, in order, right after the header; each is raw deflate of:
    uv nCells { uv keyDelta, uv points }*     key delta from the block's first key
    uv nNames { uv shared, uv len, suffix }*  sorted, deduplicated, front-coded
    sv dLat[n]  sv dLon[n]     delta from the previous point; the first from the
                               south-west corner of the block's first cell
    uv category[n]  uv name[n]  uv nameJa[n] (0 = none, else index + 1)
    uv osm[n]                  id * 4 + type (0 node, 1 way, 2 relation)

Cell key: Morton order (bits of row and column interleaved, row in the odd
bits), so that a block covers a compact area. Points are ordered by cell
key, then latitude, longitude and OSM id; the same input gives the same bytes.

Standard library only (Python 3.9+).
"""
import argparse
import collections
import hashlib
import json
import os
import sys
import zlib

SCALE = 100_000
CELL = 1_000  # 0.01 degree
TYPES = {"n": 0, "w": 1, "r": 2}
TYPE_NAMES = {"n": "node", "w": "way", "r": "relation"}

# Values that are not places one checks in to (see the module docstring).
KEEP_ONLY = {"railway": {"station", "halt", "tram_stop"}, "public_transport": {"station"}}
DROP = {
    "amenity": {"parking", "parking_space", "parking_entrance", "bicycle_parking", "motorcycle_parking",
                "bench", "waste_basket", "vending_machine", "toilets", "drinking_water", "shelter",
                "telephone", "post_box", "atm", "charging_station", "bicycle_rental", "recycling"},
    "tourism": {"information"},
    "leisure": {"pitch", "playground", "swimming_pool", "track", "slipway", "picnic_table"},
    "historic": {"memorial", "boundary_stone", "milestone", "wayside_shrine", "wayside_cross"},
}


def checkin_venue(key, value):
    if key in KEEP_ONLY:
        return value in KEEP_ONLY[key]
    return value not in DROP.get(key, ())


def uv(out, v):
    if v < 0:
        raise ValueError(v)
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


def morton(row, col):
    k = 0
    for i in range(16):
        k |= ((col >> i) & 1) << (2 * i)
        k |= ((row >> i) & 1) << (2 * i + 1)
    return k


def load(path, keep_all):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            t, i, lat, lon, k, v, name, ja = line.rstrip("\n").split("\t")
            if keep_all or checkin_venue(k, v):
                rows.append((int(lat), int(lon), t, int(i), k, v, name, ja if ja != name else ""))
    return rows


def front_coded(names):
    out = bytearray()
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
    return out


def dictionary(rows, min_count=3, size=32 * 1024):
    """Preset deflate dictionary: the most frequent names and categories,
    the most frequent last (closest to the data)."""
    counts = collections.Counter(r[6] for r in rows)
    counts.update(r[7] for r in rows if r[7])
    out = b""
    for s, n in counts.most_common():
        if n < min_count:
            break
        b = s.encode("utf-8")
        if len(out) + len(b) > size:
            break
        out = b + out
    return out


def encode(rows, block_points, zdict=b""):
    south = min(r[0] for r in rows)
    west = min(r[1] for r in rows)
    south -= south % CELL
    west -= west % CELL
    nrows = (max(r[0] for r in rows) - south) // CELL + 1
    ncols = (max(r[1] for r in rows) - west) // CELL + 1
    if max(nrows, ncols) >= 1 << 16:
        raise ValueError("grid too large")

    def cell_of(r):
        return (r[0] - south) // CELL, (r[1] - west) // CELL

    keyed = sorted(((morton(*cell_of(r)), r) for r in rows), key=lambda kr: (kr[0], kr[1][0], kr[1][1], kr[1][3], kr[1][2]))
    cats = collections.Counter(f"{r[4]}={r[5]}" for r in rows)
    cat_order = sorted(cats.items(), key=lambda cn: (-cn[1], cn[0]))
    cat_ids = {c: i for i, (c, _) in enumerate(cat_order)}

    # Blocks of whole cells, about block_points points each.
    blocks = []
    cur = []
    i = 0
    while i < len(keyed):
        j = i
        while j < len(keyed) and keyed[j][0] == keyed[i][0]:
            j += 1
        if cur and len(cur) + (j - i) > block_points:
            blocks.append(cur)
            cur = []
        cur.extend(keyed[i:j])
        i = j
    if cur:
        blocks.append(cur)

    index = bytearray()
    uv(index, len(blocks))
    body = bytearray()
    last_first = 0
    for blk in blocks:
        first = blk[0][0]
        out = bytearray()
        cells = collections.OrderedDict()
        for k, _ in blk:
            cells[k] = cells.get(k, 0) + 1
        uv(out, len(cells))
        for k, n in cells.items():
            uv(out, k - first)
            uv(out, n)
        names = sorted({r[6] for _, r in blk} | {r[7] for _, r in blk if r[7]}, key=lambda s: s.encode("utf-8"))
        name_ids = {s: n for n, s in enumerate(names)}
        out += front_coded(names)
        r0 = blk[0][1]
        crow, ccol = cell_of(r0)
        plat, plon = south + crow * CELL, west + ccol * CELL
        for _, r in blk:
            sv(out, r[0] - plat)
            plat = r[0]
        for _, r in blk:
            sv(out, r[1] - plon)
            plon = r[1]
        for _, r in blk:
            uv(out, cat_ids[f"{r[4]}={r[5]}"])
        for _, r in blk:
            uv(out, name_ids[r[6]])
        for _, r in blk:
            uv(out, name_ids[r[7]] + 1 if r[7] else 0)
        for _, r in blk:
            uv(out, r[3] * 4 + TYPES[r[2]])
        c = zlib.compressobj(9, zlib.DEFLATED, -15, 9, zlib.Z_DEFAULT_STRATEGY, zdict) if zdict else \
            zlib.compressobj(9, zlib.DEFLATED, -15, 9)
        z = c.compress(bytes(out)) + c.flush()
        uv(index, first - last_first)
        uv(index, len(blk))
        uv(index, len(z))
        last_first = first
        body += z

    header = bytearray()
    uv(header, SCALE)
    uv(header, CELL)
    sv(header, south)
    sv(header, west)
    uv(header, nrows)
    uv(header, ncols)
    uv(header, len(cat_order))
    for c, _ in cat_order:
        b = c.encode("utf-8")
        uv(header, len(b))
        header += b
    uv(header, len(zdict))
    header += zdict
    header += index
    return b"AVEN" + bytes([2, 0]) + len(header).to_bytes(4, "big") + bytes(header) + bytes(body), len(blocks)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


ATTRIBUTION = """施設データ（venues.aven）の出典 / Sources of the venue data

- © OpenStreetMap contributors。Open Database License 1.0
  https://www.openstreetmap.org/copyright  https://opendatacommons.org/licenses/odbl/1-0/
  venues.aven は OpenStreetMap のデータから作成した Derivative Database であり、ODbL 1.0 の下で
  提供する。生成の手順は https://github.com/sksat/ashidori の tools/venues/ に記す。
- 入力: Geofabrik の抽出 {source_file}（OpenStreetMap {source_date} 時点、sha256 {source_sha256}、
  https://download.geofabrik.de/asia/japan.html）
  加工: name と種別のキー（amenity, shop, tourism, leisure, railway, public_transport, office,
  historic, aeroway）を持つノード・ウェイ・リレーションから、チェックインの対象とならないもの
  （線路、ホーム、駐車場、案内板など）を除いて抜き出し、ウェイとリレーションは外接矩形の中心に
  置き、施設データの形式に変換（ashidori）
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tsv")
    ap.add_argument("out")
    ap.add_argument("--source-file", default="")
    ap.add_argument("--source-sha256", default="")
    ap.add_argument("--source-date", default="")
    ap.add_argument("--tool", action="append", default=[])
    ap.add_argument("--all", action="store_true", help="keep every feature, not only check-in venues")
    ap.add_argument("--block-points", type=int, default=2048)
    ap.add_argument("--no-dict", action="store_true", help="no preset dictionary")
    ap.add_argument("--dict-min", type=int, default=3, help="names used at least this often go in the dictionary")
    a = ap.parse_args()

    rows = load(a.tsv, a.all)
    if not rows:
        sys.exit("no venues")
    data, nblocks = encode(rows, a.block_points, b"" if a.no_dict else dictionary(rows, a.dict_min))
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, "venues.aven")
    with open(path + ".part", "wb") as f:
        f.write(data)
    os.replace(path + ".part", path)

    attribution = ATTRIBUTION.format(source_file=a.source_file or "(unknown)", source_date=a.source_date or "(unknown)",
                                     source_sha256=a.source_sha256 or "(unknown)")
    with open(os.path.join(a.out, "ATTRIBUTION"), "w", encoding="utf-8") as f:
        f.write(attribution)
    date = a.source_date[:10] if a.source_date else ""
    manifest = {
        "format": "AVEN 2",
        "source": {"file": a.source_file, "sha256": a.source_sha256, "osmDate": a.source_date,
                   "url": "https://download.geofabrik.de/asia/japan.html"},
        "selection": "all" if a.all else "checkin",
        "venues": len(rows),
        "byKey": dict(sorted(collections.Counter(r[4] for r in rows).items(), key=lambda kv: -kv[1])),
        "byType": {TYPE_NAMES[t]: n for t, n in sorted(collections.Counter(r[2] for r in rows).items())},
        "blocks": nblocks,
        "file": "venues.aven",
        "bytes": len(data),
        "sha256": sha256_file(path),
        "tools": a.tool,
        "attribution": [
            "© OpenStreetMap contributors（ODbL 1.0、https://www.openstreetmap.org/copyright）",
            f"Geofabrik の抽出 {a.source_file}（OpenStreetMap {date} 時点）。加工: チェックインの対象となる施設を抜き出し、施設データの形式に変換（ashidori）",
        ],
    }
    with open(os.path.join(a.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    json.dump({k: manifest[k] for k in ("venues", "blocks", "bytes", "sha256", "byKey")}, sys.stdout, ensure_ascii=False)
    print()


if __name__ == "__main__":
    main()
