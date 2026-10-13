#!/usr/bin/env python3
"""Assembles the offline basemap: style.json, glyphs, sprites, manifest and
ATTRIBUTION, next to japan.pmtiles and world.pmtiles (see build.sh).

Glyphs: MapLibre Native draws CJK ideographs, kana and fullwidth forms with the
system font (localIdeographFontFamily, on by default on Android), so only the
256-codepoint ranges holding other characters that occur in the tiles' strings
are fetched. The set mirrors mln::util::i18n::allowsFixedWidthGlyphGeneration.

The style's sources point at "pmtiles://file://@BASEMAP_DIR@/<name>.pmtiles";
the app substitutes the directory it copied the archives to.

Standard library only.
"""
import argparse
import gzip
import hashlib
import json
import os
import struct
import sys
import urllib.parse
import urllib.request

ASSETS = "https://raw.githubusercontent.com/protomaps/basemaps-assets/{commit}/{path}"
SPRITES = ["light.json", "light.png", "light@2x.json", "light@2x.png"]
# BMP ranges drawn locally by MapLibre Native (i18n.cpp, allowsFixedWidthGlyphGeneration).
LOCAL = [
    (0x02EA, 0x02EB), (0x1100, 0x11FF), (0x2E80, 0x2EFF), (0x2F00, 0x2FDF), (0x3000, 0x303F),
    (0x3040, 0x309F), (0x30A0, 0x30FF), (0x3105, 0x312F), (0x3131, 0x318E), (0x31A0, 0x31BF),
    (0x31C0, 0x31EF), (0x31F0, 0x31FF), (0x3200, 0x32FF), (0x3300, 0x33FF), (0x3400, 0x4DBF),
    (0x4E00, 0x9FFF), (0xA000, 0xA48C), (0xA490, 0xA4C6), (0xA960, 0xA97C), (0xAC00, 0xD7AF),
    (0xD7B0, 0xD7C6), (0xD7CB, 0xD7FB), (0xF900, 0xFA6D), (0xFA70, 0xFAD9), (0xFE10, 0xFE1F),
    (0xFE30, 0xFE4F), (0xFF00, 0xFFEF),
]


def is_local(cp):
    return any(a <= cp <= b for a, b in LOCAL)


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
    if kind == 1:  # none
        return data
    if kind == 2:  # gzip
        return gzip.decompress(data)
    raise ValueError(f"unsupported compression {kind}")


class PMTiles:
    """Minimal PMTiles v3 reader: header, metadata and every distinct tile."""

    def __init__(self, path):
        self.f = open(path, "rb")
        h = self.f.read(127)
        if h[:7] != b"PMTiles" or h[7] != 3:
            raise ValueError(f"{path}: not a PMTiles v3 archive")
        (self.root_off, self.root_len, self.meta_off, self.meta_len, self.leaf_off, self.leaf_len,
         self.data_off, self.data_len) = struct.unpack_from("<8Q", h, 8)
        self.internal, self.tile_comp, self.tile_type, self.minzoom, self.maxzoom = h[97], h[98], h[99], h[100], h[101]

    def read(self, off, n):
        self.f.seek(off)
        return self.f.read(n)

    def metadata(self):
        return json.loads(decompress(self.read(self.meta_off, self.meta_len), self.internal))

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
            n_, i = varint(buf, i)
            lens.append(n_)
        for k in range(count):
            o, i = varint(buf, i)
            offs.append(offs[k - 1] + lens[k - 1] if o == 0 and k > 0 else o - 1)
        return zip(ids, runs, lens, offs)

    def tiles(self):
        seen = set()
        stack = [(self.root_off, self.root_len)]
        while stack:
            off, n = stack.pop()
            for _, run, length, o in self.directory(off, n):
                if run == 0:
                    stack.append((self.leaf_off + o, length))
                elif o not in seen:
                    seen.add(o)
                    yield decompress(self.read(self.data_off + o, length), self.tile_comp)


def fields(buf):
    """(field number, wire type, value) of a protobuf message."""
    i = 0
    while i < len(buf):
        key, i = varint(buf, i)
        num, wt = key >> 3, key & 7
        if wt == 0:
            v, i = varint(buf, i)
        elif wt == 1:
            v, i = buf[i:i + 8], i + 8
        elif wt == 2:
            n, i = varint(buf, i)
            v, i = buf[i:i + n], i + n
        elif wt == 5:
            v, i = buf[i:i + 4], i + 4
        else:
            raise ValueError(f"wire type {wt}")
        yield num, wt, v


def label_key(k):
    """Properties the style can draw as text (see the label layers' text-field)."""
    return k.startswith(("name", "pgf:name", "ref")) or k in ("shield_text", "addr_housenumber")


def tile_strings(tile):
    """String values of label properties: Tile.layers(3) with keys(3),
    values(4, string_value 1) and features(2) whose tags(2) pair them."""
    for num, wt, layer in fields(tile):
        if num != 3 or wt != 2:
            continue
        keys, values, features = [], [], []
        for lnum, lwt, v in fields(layer):
            if lnum == 3 and lwt == 2:
                keys.append(v.decode("utf-8", "replace"))
            elif lnum == 4 and lwt == 2:
                s = None
                for vnum, vwt, x in fields(v):
                    if vnum == 1 and vwt == 2:
                        s = x.decode("utf-8", "replace")
                values.append(s)
            elif lnum == 2 and lwt == 2:
                features.append(v)
        wanted = {i for i, k in enumerate(keys) if label_key(k)}
        used = set()
        for feat in features:
            for fnum, fwt, tags in fields(feat):
                if fnum == 2 and fwt == 2:
                    i, ints = 0, []
                    while i < len(tags):
                        x, i = varint(tags, i)
                        ints.append(x)
                    used.update(vi for ki, vi in zip(ints[0::2], ints[1::2]) if ki in wanted)
        for vi in used:
            if vi < len(values) and values[vi] is not None:
                yield values[vi]


def text_fonts(layers):
    """Font stacks named in text-font, literal or inside expressions."""
    stacks = set()

    def walk(v):
        if isinstance(v, list):
            if v and all(isinstance(x, str) for x in v) and v[0] not in ("literal", "get", "coalesce", "case", "step", "match"):
                stacks.add(",".join(v))
            for x in v:
                walk(x)

    for l in layers:
        tf = l.get("layout", {}).get("text-font")
        if tf is not None:
            walk(tf)
    return sorted(s for s in stacks if s.startswith("Noto"))


def download(url, dest):
    if not os.path.exists(dest):
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with urllib.request.urlopen(url) as r:
            data = r.read()
        with open(dest + ".part", "wb") as f:
            f.write(data)
        os.replace(dest + ".part", dest)
    with open(dest, "rb") as f:
        return f.read()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--layers", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--assets-commit", required=True)
    ap.add_argument("--build", required=True)
    ap.add_argument("--japan-maxzoom", type=int, required=True)
    ap.add_argument("--world-maxzoom", type=int, required=True)
    ap.add_argument("--tool", action="append", default=[])
    a = ap.parse_args()

    with open(a.layers, encoding="utf-8") as f:
        gen = json.load(f)

    archives = {name: PMTiles(os.path.join(a.out, f"{name}.pmtiles")) for name in ("world", "japan")}
    meta = {name: pm.metadata() for name, pm in archives.items()}

    # Characters of all strings in the tiles that MapLibre does not draw locally.
    ranges = set()
    tiles = 0
    for pm in archives.values():
        for t in pm.tiles():
            tiles += 1
            for s in tile_strings(t):
                for ch in s:
                    cp = ord(ch)
                    if cp <= 0xFFFF and not is_local(cp):
                        ranges.add(cp // 256)
    ranges.add(0)  # digits and Latin are always needed
    print(f"{tiles} tiles; glyph ranges: {sorted(ranges)}", file=sys.stderr)

    def renamed(layers, source, drop_background=False):
        out = []
        for l in layers:
            if drop_background and l["type"] == "background":
                continue
            l = dict(l)
            l["id"] = f"{source}-{l['id']}"
            out.append(l)
        return out

    # Japan above the world, labels above all shapes so that the Japan
    # archive's land does not cover the world's labels near its edges.
    layers = (renamed(gen["world"]["base"], "world") + renamed(gen["japan"]["base"], "japan", True)
              + renamed(gen["world"]["labels"], "world") + renamed(gen["japan"]["labels"], "japan"))
    credit = ('<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a> '
              '<a href="https://protomaps.com/">Protomaps</a> '
              '<a href="https://www.naturalearthdata.com/">Natural Earth</a>')
    style = {
        "version": 8,
        "name": "ashidori offline",
        "sources": {
            name: {"type": "vector", "url": f"pmtiles://file://@BASEMAP_DIR@/{name}.pmtiles", "attribution": credit}
            for name in ("world", "japan")
        },
        "glyphs": "asset://basemap/fonts/{fontstack}/{range}.pbf",
        "sprite": "asset://basemap/sprites/light",
        "layers": layers,
    }
    with open(os.path.join(a.out, "style.json"), "w", encoding="utf-8") as f:
        json.dump(style, f, ensure_ascii=False, separators=(",", ":"))

    files = {}
    cache = os.path.join(a.work, "basemaps-assets", a.assets_commit)
    stacks = text_fonts(layers)
    for stack in stacks:
        for r in sorted(ranges):
            name = f"{r * 256}-{r * 256 + 255}.pbf"
            rel = f"fonts/{stack}/{name}"
            url = ASSETS.format(commit=a.assets_commit, path=urllib.parse.quote(rel))
            data = download(url, os.path.join(cache, rel))
            os.makedirs(os.path.join(a.out, "fonts", stack), exist_ok=True)
            with open(os.path.join(a.out, rel), "wb") as f:
                f.write(data)
            files[rel] = data
        # Every other BMP range gets an empty glyph file (a valid, empty
        # protobuf). MapLibre also asks for ranges that are not in the tiles'
        # strings, e.g. the Arabic presentation forms it shapes text into;
        # a missing file fails the whole snapshot, an empty one just draws
        # nothing for those characters.
        for r in range(256):
            if r in ranges:
                continue
            rel = f"fonts/{stack}/{r * 256}-{r * 256 + 255}.pbf"
            with open(os.path.join(a.out, rel), "wb"):
                pass
    for s in SPRITES + []:
        rel = f"sprites/v4/{s}"
        data = download(ASSETS.format(commit=a.assets_commit, path=rel), os.path.join(cache, rel))
        os.makedirs(os.path.join(a.out, "sprites"), exist_ok=True)
        with open(os.path.join(a.out, "sprites", s), "wb") as f:
            f.write(data)
        files[f"sprites/{s}"] = data
    ofl = download(ASSETS.format(commit=a.assets_commit, path="fonts/OFL.txt"), os.path.join(cache, "fonts/OFL.txt"))
    with open(os.path.join(a.out, "OFL.txt"), "wb") as f:
        f.write(ofl)

    sizes = {}
    for name in ("japan.pmtiles", "world.pmtiles", "style.json", "OFL.txt"):
        with open(os.path.join(a.out, name), "rb") as f:
            files[name] = f.read()
    for rel, data in files.items():
        sizes[rel] = {"bytes": len(data), "sha256": sha256(data)}
    total = sum(v["bytes"] for v in sizes.values())

    attribution = [
        "© OpenStreetMap contributors（ODbL 1.0、https://www.openstreetmap.org/copyright）",
        f"Protomaps Basemap（{a.build} 版、https://protomaps.com/）。"
        f"加工: 日本（ズーム 6〜{a.japan_maxzoom}）と全世界（ズーム 0〜{a.world_maxzoom}）を切り出し（ashidori）",
        "Natural Earth（パブリックドメイン、https://www.naturalearthdata.com/）",
        "Noto Sans（SIL Open Font License 1.1）。字形データは protomaps/basemaps-assets による",
        "アイコン: protomaps/basemaps-assets（tangrams/icons に由来、MIT License）",
        "地図の配色: @protomaps/basemaps（BSD-3-Clause）",
    ]
    manifest = {
        "format": "ashidori basemap v1",
        "attribution": attribution,
        "build": a.build,
        "source": f"https://build.protomaps.com/{a.build}.pmtiles",
        "zoom": {"japan": [6, a.japan_maxzoom], "world": [0, a.world_maxzoom]},
        "tilesMetadata": {name: {k: m.get(k) for k in ("version", "attribution", "type") if k in m}
                          for name, m in meta.items()},
        "tools": a.tool,
        "basemapsAssetsCommit": a.assets_commit,
        "fontStacks": stacks,
        "glyphRanges": [f"{r * 256}-{r * 256 + 255}" for r in sorted(ranges)],
        "totalBytes": total,
        "files": dict(sorted(sizes.items())),
    }
    with open(os.path.join(a.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")

    notice = [
        "オフライン地図（basemap）の出典 / Sources of the offline basemap",
        "",
        "このディレクトリのファイルは tools/basemap/build.sh により次のデータから作成した。",
        "",
        *[f"- {l}" for l in attribution],
        "",
        "japan.pmtiles と world.pmtiles は OpenStreetMap のデータベースから作成された",
        "Produced Work であり、OpenStreetMap のデータは Open Database License 1.0",
        "（https://opendatacommons.org/licenses/odbl/1-0/）の下で提供される。",
        "フォントのライセンスは OFL.txt を参照。",
        "",
    ]
    with open(os.path.join(a.out, "ATTRIBUTION"), "w", encoding="utf-8") as f:
        f.write("\n".join(notice))
    print(f"total {total:,} bytes", file=sys.stderr)


if __name__ == "__main__":
    main()
