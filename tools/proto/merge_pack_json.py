#!/usr/bin/env python3
"""Merges the per-pack JSON of pmtiles_list.py into one file keyed by pack.

  merge_pack_json.py <out.json> <pack.pmtiles.json>...

Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
"""
import json
import os
import sys

out = {}
for f in sys.argv[2:]:
    out[os.path.basename(f)[: -len(".pmtiles.json")]] = json.load(open(f))
with open(sys.argv[1], "w") as fh:
    json.dump(out, fh, indent=1, sort_keys=True)
    fh.write("\n")
