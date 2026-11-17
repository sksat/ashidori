#!/usr/bin/env python3
"""Summarizes the TSV of dryrun_cells.sh: sizes in bytes (go-pmtiles prints
SI units rounded to 3 digits), the distribution, and the cells by size.

  summarize_dryrun.py <tsv> [top N]

Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
"""
import sys

UNIT = {"B": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9}


def size(s):
    v, u = s.split()
    return float(v) * UNIT[u]


rows = []
for line in open(sys.argv[1]).read().splitlines()[1:]:
    cell, tiles, entries, archive = line.split("\t")
    rows.append((cell, int(tiles), int(entries), size(archive), archive))
rows.sort(key=lambda r: -r[3])
top = int(sys.argv[2]) if len(sys.argv) > 2 else 20
sizes = sorted(r[3] for r in rows)
n = len(sizes)


def pct(p):
    return sizes[min(n - 1, int(p * n))]


print(f"cells {n}, all with {set(r[1] for r in rows)} region tiles")
print(f"total {sum(sizes) / 1e9:.2f} GB")
for label, th in (("<= 1 MB", 1e6), ("<= 10 MB", 1e7), ("<= 50 MB", 5e7), ("<= 100 MB", 1e8), ("<= 200 MB", 2e8)):
    print(f"{label}: {sum(1 for s in sizes if s <= th)}")
print(f"median {pct(0.5) / 1e6:.1f} MB, p75 {pct(0.75) / 1e6:.1f} MB, p90 {pct(0.9) / 1e6:.1f} MB, max {sizes[-1] / 1e6:.1f} MB")
print("largest:")
for r in rows[:top]:
    print(f"  {r[0]}\t{r[4]}")
