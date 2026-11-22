#!/usr/bin/env python3
"""Converts the National Police Agency's list of 高度化PICS intersections into
the app's bundled list (docs/design/pics.md 4.3).

    tools/pics/build.py <the CSV as downloaded> [<output dir>]

Input: 「全国高度化PICS整備交差点（CSV版）」 (Shift_JIS), from
https://www.npa.go.jp/bureau/traffic/seibi2/annzen-shisetu/utms/pics.html
(fetch.sh downloads it). Output (default app/src/main/assets/pics):

- sites.csv: UTF-8, `pref,city,name,lat,lon`, one intersection a line, in
  the source's order (the reading, the address and the empty trailing
  columns are left out; PicsSites.parse reads it).
- manifest.json: the source, its date (現在日), sha256, size and rows, the
  output's sha256, and the attribution.

Checks: the header, every row's column count, the coordinates inside Japan,
no comma or quote in a kept field, and the row count. Anything else stops
the build: the list is not written half right.
"""
import csv
import hashlib
import io
import json
import os
import sys

SOURCE_URL = "https://www.npa.go.jp/bureau/traffic/seibi2/annzen-shisetu/utms/pics.html"
# The date the list states (令和8年4月1日現在); change with a new list.
AS_OF = "2026-04-01"
AS_OF_JA = "令和8年4月1日現在"
HEADER = ["都道府県", "市町村", "交差点名称", "よみ方", "所在地", "緯度", "経度"]
ATTRIBUTION = (
    f"出典：警察庁ウェブサイト「全国高度化PICS整備交差点（CSV版）」（{AS_OF_JA[:-2]}現在）"
    f" {SOURCE_URL} を加工して作成（文字コードと列の変換。ashidori）"
)


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    src = argv[1]
    out = argv[2] if len(argv) > 2 else os.path.join("app", "src", "main", "assets", "pics")
    raw = open(src, "rb").read()
    text = raw.decode("cp932")
    rows = list(csv.reader(io.StringIO(text)))
    if [c.strip() for c in rows[0][: len(HEADER)]] != HEADER:
        sys.exit(f"unexpected header: {rows[0]}")
    sites = []
    for n, r in enumerate(rows[1:], start=2):
        if not any(c.strip() for c in r):
            continue
        if len(r) < len(HEADER):
            sys.exit(f"line {n}: {len(r)} columns")
        pref, city, name = (c.strip() for c in r[:3])
        lat, lon = float(r[5]), float(r[6])
        if not (20 <= lat <= 46 and 122 <= lon <= 154):
            sys.exit(f"line {n}: {lat},{lon} outside Japan")
        for c in (pref, city, name):
            if not c or "," in c or '"' in c or "\n" in c:
                sys.exit(f"line {n}: field {c!r} cannot be kept as it is")
        sites.append((pref, city, name, r[5].strip(), r[6].strip()))
    if not 100 <= len(sites) <= 100_000:
        sys.exit(f"{len(sites)} rows: not the list")
    os.makedirs(out, exist_ok=True)
    body = "pref,city,name,lat,lon\n" + "".join(",".join(s) + "\n" for s in sites)
    data = body.encode("utf-8")
    with open(os.path.join(out, "sites.csv"), "wb") as f:
        f.write(data)
    manifest = {
        "format": "pics-sites v1",
        "attribution": [ATTRIBUTION],
        "source": {
            "title": "全国高度化PICS整備交差点（CSV版）",
            "publisher": "警察庁",
            "url": SOURCE_URL,
            "asOf": AS_OF,
            "terms": "公共データ利用規約（第1.0版）（PDL1.0）に準拠（警察庁ウェブサイトの利用規約）",
            "file": os.path.basename(src),
            "bytes": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "encoding": "Shift_JIS",
            "rows": len(sites),
        },
        "output": {"file": "sites.csv", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "rows": len(sites)},
        "processing": "文字コードを UTF-8 に変換し、都道府県・市町村・交差点名称・緯度・経度の列を抜き出した",
    }
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{len(sites)} sites -> {out}")


if __name__ == "__main__":
    main(sys.argv)
