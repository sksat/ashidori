#!/usr/bin/env python3
"""Builds the offline reverse-geocoding index used by ashidori.

Inputs
  --isj       位置参照情報 大字・町丁目レベル (国土交通省), per-prefecture ZIPs
              (e.g. 13000-19.0b.zip) or their CSVs (Shift_JIS).
  --geonames  GeoNames cities file (cities1000.zip / cities500.zip or the .txt).
              Japanese places are taken from 位置参照情報 only; GeoNames rows
              with country code JP are skipped.

Output (--out): a gzip-compressed binary file, read by
ashidori.core.ReverseGeocoder.load. All integers are big-endian; "uv" is an
unsigned LEB128 varint and "sv" a zigzag-encoded signed varint.

  "AGEO" u8 version(=1) uv scale        coordinates are round(deg * scale)
  uv nStrings  { uv byteLength, UTF-8 bytes }*    deduplicated strings
  uv nRegions  { uv country, uv prefecture, uv municipality }*   string ids
  uv cellDeg1e6                         grid cell size in microdegrees
  uv nCells    { uv keyDelta, uv count }*  cell key = latIdx * LonCells + lonIdx
  uv nPoints                            points ordered by cell key
  u8 kind[nPoints]                      0 = Japanese town (大字・町丁目), 1 = city abroad
  u8 size[nPoints]                      round(10 * log10(population)), 0 if unknown or in Japan
  sv lat delta[nPoints]  sv lon delta[nPoints]
  uv name[nPoints]  uv region[nPoints]

A manifest (--manifest) records the input files, their sha256 and the counts.
Standard library only, so the build is reproducible with any Python 3.9+.
"""
import argparse
import csv
import gzip
import hashlib
import io
import json
import math
import os
import re
import sys
import zipfile

VERSION = 1
SCALE = 100_000          # 1e-5 degree, about 1.1 m
CELL_DEG = 0.02          # about 2.2 km north-south
LAT_CELLS = int(round(180 / CELL_DEG))
LON_CELLS = int(round(360 / CELL_DEG))
KANA = re.compile("[\u3040-\u30ff]")
HAN_ONLY = re.compile("^[\u4e00-\u9fff]+$")
# Sections of a city ("Paris 16 Passy", "Times Square") would hide the city
# itself; abandoned, historical and destroyed places are not current names.
SKIP_FEATURES = {"PPLX", "PPLQ", "PPLH", "PPLW"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def text_members(path, suffix):
    """(name, bytes) of the data files in a ZIP, or of a plain file."""
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            for info in sorted(z.infolist(), key=lambda i: i.filename):
                if info.filename.lower().endswith(suffix):
                    yield info.filename, z.read(info)
    else:
        with open(path, "rb") as f:
            yield os.path.basename(path), f.read()


def read_isj(paths):
    """Rows of (pref, municipality, town, lat, lon) from 大字・町丁目 CSVs."""
    out, members = [], []
    for p in paths:
        for name, data in text_members(p, ".csv"):
            members.append(name)
            rows = csv.reader(io.StringIO(data.decode("cp932")))
            header = next(rows)
            if header[:6] != ["都道府県コード", "都道府県名", "市区町村コード", "市区町村名", "大字町丁目コード", "大字町丁目名"]:
                sys.exit(f"{p}:{name}: not a 大字・町丁目レベル CSV (header {header[:6]})")
            for r in rows:
                if len(r) < 8 or not r[5]:
                    continue
                out.append((r[1], r[3], r[5], float(r[6]), float(r[7])))
    return out, members


def world_name(name, alternates, cc):
    """A name for a city abroad: Japanese (kana) if GeoNames has one,
    Han characters for the Chinese-speaking regions, else the GeoNames name."""
    alts = [a for a in alternates.split(",") if a]
    kana = [a for a in alts if KANA.search(a)]
    if kana:
        return min(kana, key=len)
    if cc in ("CN", "TW", "HK", "MO"):
        han = [a for a in alts if HAN_ONLY.match(a)]
        if han:
            return min(han, key=len)
    return name


def read_geonames(paths):
    out, members = [], []
    for p in paths:
        for name, data in text_members(p, ".txt"):
            if name.lower().startswith("readme"):
                continue
            members.append(name)
            for line in data.decode("utf-8").splitlines():
                f = line.split("\t")
                if len(f) < 9 or f[8] == "JP" or f[7] in SKIP_FEATURES:
                    continue
                pop = int(f[14]) if len(f) > 14 and f[14].isdigit() else 0
                out.append((f[8], world_name(f[1], f[3], f[8]), float(f[4]), float(f[5]), pop))
    return out, members


def uv(buf, v):
    assert v >= 0
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            buf.append(b | 0x80)
        else:
            buf.append(b)
            return


def sv(buf, v):
    uv(buf, (v << 1) if v >= 0 else ((-v << 1) - 1))


def cell_key(qlat, qlon):
    lat_i = min(LAT_CELLS - 1, max(0, math.floor((qlat / SCALE + 90) / CELL_DEG)))
    lon_i = min(LON_CELLS - 1, max(0, math.floor((qlon / SCALE + 180) / CELL_DEG)))
    return lat_i * LON_CELLS + lon_i


def build(jp, world):
    strings, sid = [], {}

    def s(x):
        if x not in sid:
            sid[x] = len(strings)
            strings.append(x)
        return sid[x]

    regions, rid = [], {}

    def region(cc, pref, muni):
        k = (s(cc), s(pref), s(muni))
        if k not in rid:
            rid[k] = len(regions)
            regions.append(k)
        return rid[k]

    s("")  # id 0: empty
    pts = []
    for pref, muni, town, lat, lon in jp:
        pts.append((0, round(lat * SCALE), round(lon * SCALE), s(town), region("JP", pref, muni), 0))
    for cc, name, lat, lon, pop in world:
        size = min(255, round(10 * math.log10(pop))) if pop > 0 else 0
        pts.append((1, round(lat * SCALE), round(lon * SCALE), s(name), region(cc, "", ""), size))
    # Deterministic order: by cell, then position, then name.
    pts.sort(key=lambda p: (cell_key(p[1], p[2]), p[1], p[2], p[0], p[3]))

    buf = bytearray(b"AGEO")
    buf.append(VERSION)
    uv(buf, SCALE)
    uv(buf, len(strings))
    for x in strings:
        b = x.encode("utf-8")
        uv(buf, len(b))
        buf += b
    uv(buf, len(regions))
    for r in regions:
        for v in r:
            uv(buf, v)
    uv(buf, int(round(CELL_DEG * 1e6)))
    cells = []
    for p in pts:
        k = cell_key(p[1], p[2])
        if cells and cells[-1][0] == k:
            cells[-1][1] += 1
        else:
            cells.append([k, 1])
    uv(buf, len(cells))
    prev = 0
    for k, n in cells:
        uv(buf, k - prev)
        uv(buf, n)
        prev = k
    uv(buf, len(pts))
    buf += bytes(p[0] for p in pts)
    buf += bytes(p[5] for p in pts)
    for col in (1, 2):
        prev = 0
        for p in pts:
            sv(buf, p[col] - prev)
            prev = p[col]
    for p in pts:
        uv(buf, p[3])
    for p in pts:
        uv(buf, p[4])
    return bytes(buf), {"strings": len(strings), "regions": len(regions), "cells": len(cells), "points": len(pts)}


ISJ_TITLE = "大字・町丁目レベル位置参照情報　国土交通省"
CC_BY = "https://creativecommons.org/licenses/by/4.0/"


def zip_date(path):
    """Date of the first .txt member of a ZIP (GeoNames' export date), or ""."""
    if path.lower().endswith(".zip"):
        with zipfile.ZipFile(path) as z:
            txt = [i for i in z.infolist() if i.filename.endswith(".txt")]
            if txt:
                y, mo, d = txt[0].date_time[:3]
                return f"{y}-{mo:02d}-{d:02d}"
    return ""


def attribution(isj_files, isj_members, gn_files):
    """Credit lines shown in the app (データの出典) and written to ATTRIBUTION.

    位置参照情報利用約款 第2条第3項: name the data as 「大字・町丁目レベル位置参照情報
    国土交通省」 and, where possible, the year, the file names and who processed
    it. GeoNames (CC BY 4.0): credit, the licence link, and that it was modified.
    """
    lines = []
    if isj_files:
        names = sorted(f["file"] for f in isj_files)
        versions = sorted({m.group(1) for n in names if (m := re.search(r"-(\d+\.\d+[a-z]?)\.zip$", n))})
        years = sorted({m.group(1) for n in isj_members if (m := re.search(r"_(\d{4})\.csv$", n))})
        files = names[0] if len(names) == 1 else f"{names[0]}〜{names[-1]}（{len(names)} ファイル）"
        detail = "、".join(filter(None, ["・".join(f"{y}年" for y in years),
                                         "・".join(f"第{v}版" for v in versions), f"ファイル: {files}"]))
        lines.append(f"{ISJ_TITLE}（{detail}）。加工: 索引形式に変換（ashidori）")
    for f in gn_files:
        when = f"、{f['date']} 版" if f.get("date") else ""
        lines.append(f"GeoNames（{f['file']}{when}）、CC BY 4.0（{CC_BY}）。"
                     "改変: 索引形式に変換し、日本国内の地点と廃止・歴史上の地点などを除外（ashidori）")
    return lines


def write_attribution_file(path, lines):
    """Plain-text notice placed next to the generated index."""
    body = [
        "地名の索引（index.bin）の出典 / Sources of the offline geocoder index",
        "",
        "index.bin は tools/geocoder/build_index.py により次のデータを加工して作成した。",
        "",
        *[f"- {l}" for l in lines],
        "",
        "位置参照情報: https://nlftp.mlit.go.jp/isj/ （位置参照情報利用約款）",
        f"GeoNames: https://www.geonames.org/ （Creative Commons Attribution 4.0, {CC_BY}）",
        "",
    ]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(body))


def refresh(manifest_path, attribution_path):
    """Recomputes the credit lines of an existing manifest without the inputs."""
    with open(manifest_path, encoding="utf-8") as f:
        m = json.load(f)
    gn = m["inputs"]["geonames"]["files"]
    old = " ".join(m.get("attribution", []))
    for g in gn:
        if "date" not in g and (d := re.search(re.escape(g["file"]) + r"、(\d{4}-\d{2}-\d{2}) 版", old)):
            g["date"] = d.group(1)
    m["attribution"] = attribution(m["inputs"]["isj"]["files"], m["inputs"]["isj"].get("members", []), gn)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=2)
        f.write("\n")
    if attribution_path:
        write_attribution_file(attribution_path, m["attribution"])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--isj", nargs="*", default=[], help="位置参照情報 大字・町丁目レベル ZIPs or CSVs")
    ap.add_argument("--geonames", nargs="*", default=[], help="GeoNames cities ZIP or TXT")
    ap.add_argument("--out")
    ap.add_argument("--manifest")
    ap.add_argument("--attribution", help="also write a plain-text ATTRIBUTION file here")
    ap.add_argument("--refresh", action="store_true",
                    help="only recompute the credit lines of --manifest (and write --attribution)")
    a = ap.parse_args()
    if a.refresh:
        refresh(a.manifest, a.attribution)
        return
    if not a.out:
        ap.error("--out is required")

    jp, jp_members = read_isj(a.isj)
    world, gn_members = read_geonames(a.geonames)
    raw, counts = build(jp, world)
    # mtime=0 keeps the gzip output byte-identical across runs.
    data = gzip.compress(raw, compresslevel=9, mtime=0)
    with open(a.out, "wb") as f:
        f.write(data)
    counts.update(japanTowns=len(jp), worldCities=len(world))
    print(f"{a.out}: {len(data):,} bytes (uncompressed {len(raw):,}); {counts}", file=sys.stderr)
    if a.manifest:
        def inputs(paths, members):
            return [{"file": os.path.basename(p), "bytes": os.path.getsize(p), "sha256": sha256(p)} for p in paths], members
        isj_files, isj_members = inputs(a.isj, jp_members)
        gn_files, gn_members2 = inputs(a.geonames, gn_members)
        for g, p in zip(gn_files, a.geonames):
            if (d := zip_date(p)):
                g["date"] = d
        manifest = {
            "format": f"AGEO v{VERSION}",
            # Shown in the app (データの出典); see attribution().
            "attribution": attribution(isj_files, isj_members, gn_files),
            "output": {"file": os.path.basename(a.out), "bytes": len(data), "uncompressedBytes": len(raw),
                       "sha256": hashlib.sha256(data).hexdigest()},
            "counts": counts,
            "inputs": {
                "isj": {"source": "位置参照情報 大字・町丁目レベル（国土交通省）",
                        "url": "https://nlftp.mlit.go.jp/isj/", "terms": "位置参照情報利用約款",
                        "files": isj_files, "members": isj_members},
                "geonames": {"source": "GeoNames", "url": "https://download.geonames.org/export/dump/",
                             "license": "CC BY 4.0", "files": gn_files, "members": gn_members2},
            },
        }
        with open(a.manifest, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
            f.write("\n")
        if a.attribution:
            write_attribution_file(a.attribution, manifest["attribution"])


if __name__ == "__main__":
    main()
