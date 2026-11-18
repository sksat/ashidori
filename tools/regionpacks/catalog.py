#!/usr/bin/env python3
"""Writes the region packs' release files and their catalog
(docs/design/outbound-and-offline.md 6.5):

  catalog.py --cells <dir> --style-zip <zip> --style <version> --build <YYYYMMDD>
             --version <version> --out <dist> [--tool <name version>]...

<cells> holds <cell>.pmtiles and <cell>.json (verify.py pack) for every
cell. Writes to <dist>:

  ashidori-region-<cell>-<version>.pmtiles   one per cell (hard links when possible)
  ashidori-regions-style-<style>.zip         the style bundle (style.py)
  ashidori-regions-<version>.json            the catalog: per cell its file, size,
                                             sha256, bounds and zooms; the style bundle
  measure.tsv                                size, tiles and extraction time per cell

and prints the catalog's path. Refuses a pack with problems, more than 1,000
release assets, or a file of 2 GiB or more (GitHub Releases' limits).

Standard library only.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here)
import cells as cellmath  # noqa: E402

FORMAT = "ashidori regions catalog v1"
VERSION_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$")
MIN_ZOOM, MAX_ZOOM = 8, 14
MAX_ASSETS = 1000
MAX_BYTES = 2 * 1024 ** 3


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def place(src, dst):
    if os.path.exists(dst):
        os.remove(dst)
    try:
        os.link(src, dst)
    except OSError:
        shutil.copyfile(src, dst)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cells", required=True)
    ap.add_argument("--style-zip", required=True)
    ap.add_argument("--style", required=True)
    ap.add_argument("--build", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--tool", action="append", default=[])
    a = ap.parse_args()
    for v in (a.version, a.style):
        if not VERSION_RE.match(v) or ".." in v:
            sys.exit(f"invalid version {v!r}")

    os.makedirs(a.out, exist_ok=True)
    reports = sorted((n[:-5] for n in os.listdir(a.cells) if n.endswith(".json")), key=cellmath.sort_key)
    entries, rows = [], []
    for cell in reports:
        with open(os.path.join(a.cells, f"{cell}.json"), encoding="utf-8") as f:
            r = json.load(f)
        if r.get("problems"):
            sys.exit(f"{cell}: {r['problems']}")
        if (r["minzoom"], r["maxzoom"]) != (MIN_ZOOM, MAX_ZOOM):
            sys.exit(f"{cell}: zoom {r['minzoom']}-{r['maxzoom']}")
        name = f"ashidori-region-{cell}-{a.version}.pmtiles"
        dst = os.path.join(a.out, name)
        place(os.path.join(a.cells, f"{cell}.pmtiles"), dst)
        size = os.path.getsize(dst)
        if size >= MAX_BYTES:
            sys.exit(f"{cell}: {size} bytes (split it into zoom-9 cells)")
        x, y = cellmath.parse(cell)
        entries.append({
            "cell": cell,
            "style": a.style,
            "bounds": [round(v, 7) for v in cellmath.bbox(x, y)],
            "map": {"file": name, "bytes": size, "sha256": sha256_file(dst)},
            "venues": None,
        })
        rows.append((cell, size, r["tiles"], r.get("seconds", "")))

    style_name = f"ashidori-regions-style-{a.style}.zip"
    style_dst = os.path.join(a.out, style_name)
    if os.path.abspath(a.style_zip) != os.path.abspath(style_dst):
        place(a.style_zip, style_dst)
    catalog = {
        "format": FORMAT,
        "version": a.version,
        "build": a.build,
        "source": f"https://build.protomaps.com/{a.build}.pmtiles",
        "minzoom": MIN_ZOOM,
        "maxzoom": MAX_ZOOM,
        "tools": a.tool,
        "attribution": [
            "© OpenStreetMap contributors（ODbL 1.0、https://www.openstreetmap.org/copyright）",
            f"Protomaps Basemap（{a.build} 版、https://protomaps.com/）。加工: ズーム 8 の区画ごとにズーム 8〜14 を切り出し（ashidori）",
        ],
        "styles": [{"style": a.style, "file": style_name, "bytes": os.path.getsize(style_dst),
                    "sha256": sha256_file(style_dst)}],
        "cells": entries,
    }
    assets = len(entries) + 2
    if assets > MAX_ASSETS:
        sys.exit(f"{assets} release assets (at most {MAX_ASSETS})")
    path = os.path.join(a.out, f"ashidori-regions-{a.version}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")
    with open(os.path.join(a.out, "measure.tsv"), "w", encoding="utf-8") as f:
        f.write("cell\tbytes\ttiles\tseconds\n")
        for row in rows:
            f.write("\t".join(str(v) for v in row) + "\n")
    total = sum(e["map"]["bytes"] for e in entries)
    print(f"{len(entries)} cells, {total:,} bytes; {assets} assets", file=sys.stderr)
    print(path)


if __name__ == "__main__":
    main()
