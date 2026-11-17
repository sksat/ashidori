#!/usr/bin/env python3
"""Glyph ranges (256 code points each) that the label strings of PMTiles
archives need beyond what MapLibre draws locally (CJK, kana, Hangul), with
the same rules as tools/basemap/assemble.py.

  glyph_ranges.py <archive.pmtiles>...

Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "basemap"))
import assemble  # noqa: E402

allr = set()
for path in sys.argv[1:]:
    ranges = {0}
    tiles = 0
    for tile in assemble.PMTiles(path).tiles():
        tiles += 1
        for s in assemble.tile_strings(tile):
            for ch in s:
                cp = ord(ch)
                if not assemble.is_local(cp):
                    ranges.add(cp // 256)
    allr |= ranges
    print(f"{os.path.basename(path)}\t{tiles} distinct tiles\t{len(ranges)} ranges\t{sorted(ranges)}")
print(f"union\t{len(allr)} ranges\t{sorted(allr)}")
