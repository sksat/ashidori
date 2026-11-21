#!/usr/bin/env python3
"""Builds the style bundle of the region packs (docs/design/outbound-and-offline.md 6.5):

  style.py --layers <layers.json> --work <dir> --out <dir> --style <version>
           --assets-commit <commit> [--tool <name version>]...

writes <out>/ashidori-regions-style-<version>.zip holding

  layers.json                 the pack layers for the source "pack" ({"base", "labels"});
                              the app copies them per pack
  fonts/<stack>/<range>.pbf   every 256-code-point range of the stacks the layers
                              name, as protomaps/basemaps-assets has it; ranges
                              MapLibre draws with the system font (CJK, kana,
                              Hangul) and ranges without glyphs are empty files
  sprites/light{,@2x}.{json,png}
  manifest.json  ATTRIBUTION
  OFL.txt                     the fonts' license, from protomaps/basemaps-assets
  LICENSE-icons.txt           the sprites' license (tangrams/icons, MIT)
  LICENSE-basemaps.txt        the layers' license (@protomaps/basemaps, BSD-3-Clause);
                              both from tools/licenses/, which names their sources

The fonts are a superset of the offline basemap's (tools/basemap/assemble.py
fetches only the ranges its tiles use), so a style with packs can take the
bundle's fonts as its only `glyphs`. The ZIP has sorted entries and a fixed
time: the same input gives the same bytes.

Standard library only.
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.parse
import zipfile

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, "..", "basemap"))
import assemble  # noqa: E402

FORMAT = "ashidori regions style v1"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def zip_dir(src, path):
    files = []
    for root, dirs, names in os.walk(src):
        dirs.sort()
        for n in names:
            files.append(os.path.relpath(os.path.join(root, n), src).replace(os.sep, "/"))
    files.sort()
    with zipfile.ZipFile(path + ".part", "w") as z:
        for rel in files:
            info = zipfile.ZipInfo(rel, FIXED_TIME)
            info.external_attr = 0o644 << 16
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(src, rel), "rb") as f:
                z.writestr(info, f.read(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    os.replace(path + ".part", path)
    return len(files)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--layers", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--style", required=True)
    ap.add_argument("--assets-commit", required=True)
    ap.add_argument("--tool", action="append", default=[])
    a = ap.parse_args()

    with open(a.layers, encoding="utf-8") as f:
        gen = json.load(f)
    layers = {"base": gen["base"], "labels": gen["labels"]}
    for l in layers["base"] + layers["labels"]:
        if l.get("source") != "pack":
            sys.exit(f"layer {l['id']} is not of the source 'pack'")

    stage = os.path.join(a.work, f"style-{a.style}")
    assemble_files = {}
    if os.path.isdir(stage):
        import shutil
        shutil.rmtree(stage)
    os.makedirs(stage)

    def put(rel, data):
        p = os.path.join(stage, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(data)
        assemble_files[rel] = data

    put("layers.json", json.dumps({"format": FORMAT, "style": a.style, **layers},
                                  ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode())

    cache = os.path.join(a.work, "basemaps-assets", a.assets_commit)
    stacks = assemble.text_fonts(layers["base"] + layers["labels"])
    ranges = {}
    for stack in stacks:
        have = []
        for r in range(256):
            rel = f"fonts/{stack}/{r * 256}-{r * 256 + 255}.pbf"
            if all(assemble.is_local(cp) for cp in range(r * 256, r * 256 + 256)):
                put(rel, b"")
                continue
            url = assemble.ASSETS.format(commit=a.assets_commit, path=urllib.parse.quote(rel))
            data = assemble.download(url, os.path.join(cache, rel))
            put(rel, data)
            if len(data) > 0:
                have.append(r)
        ranges[stack] = have
    for s in assemble.SPRITES:
        rel = f"sprites/v4/{s}"
        data = assemble.download(assemble.ASSETS.format(commit=a.assets_commit, path=rel), os.path.join(cache, rel))
        put(f"sprites/{s}", data)
    put("OFL.txt", assemble.download(assemble.ASSETS.format(commit=a.assets_commit, path="fonts/OFL.txt"),
                                     os.path.join(cache, "fonts/OFL.txt")))
    for name in assemble.LICENSE_FILES:
        put(name, assemble.license_text(name))
    attribution = [
        "Noto Sans（SIL Open Font License 1.1）。字形データは protomaps/basemaps-assets による",
        "アイコン: protomaps/basemaps-assets（tangrams/icons に由来、MIT License）",
        "地図の配色: @protomaps/basemaps（BSD-3-Clause）",
    ]
    put("ATTRIBUTION", ("地域データのスタイル（フォント、アイコン、配色）の出典\n\n"
                        + "".join(f"- {l}\n" for l in attribution) + f"\nライセンスの全文: {assemble.LICENSE_NOTE}\n").encode())
    manifest = {
        "format": FORMAT,
        "style": a.style,
        "attribution": attribution,
        "tools": a.tool,
        "basemapsAssetsCommit": a.assets_commit,
        "fontStacks": stacks,
        "glyphRanges": {s: [f"{r * 256}-{r * 256 + 255}" for r in rs] for s, rs in ranges.items()},
        "files": {rel: {"bytes": len(d), "sha256": hashlib.sha256(d).hexdigest()}
                  for rel, d in sorted(assemble_files.items())},
    }
    put("manifest.json", (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode())

    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, f"ashidori-regions-style-{a.style}.zip")
    n = zip_dir(stage, path)
    print(f"{path}: {n} entries, {os.path.getsize(path):,} bytes", file=sys.stderr)
    print(path)


if __name__ == "__main__":
    main()
