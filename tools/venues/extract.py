#!/usr/bin/env python3
"""Extracts named venues from an OpenStreetMap PBF extract (prototype).

  extract.py <input.osm.pbf> <work dir>

Writes <work dir>/venues.tsv, one feature per line:

  type  id  lat_e5  lon_e5  key  value  name  name:ja

Selection is the same as the app's online search (ashidori.core.Overpass):
objects with a "name" tag and any of the category keys below. A way or
relation is placed at the center of its bounding box, as Overpass
`out center` does. Coordinates are round(deg * 1e5) (about 1 m).

Needs pyosmium (pip package "osmium"). The first step writes the matching
objects and the nodes and ways they reference to <work dir>/selected.osm.pbf
(osmium.BackReferenceWriter), so the node location cache of the second step
only holds those nodes and fits in memory.
"""
import os
import sys
import time

import osmium
import osmium.filter

# ashidori.core.Overpass.CategoryKeys, in priority order (without "building").
CATEGORY_KEYS = ("amenity", "shop", "tourism", "leisure", "railway", "public_transport",
                 "office", "historic", "aeroway")


def clean(s):
    return s.replace("\t", " ").replace("\n", " ").replace("\r", " ").strip()


def select(src, dst):
    if os.path.exists(dst):
        return
    t = time.time()
    tmp = dst[:-len(".osm.pbf")] + ".tmp.osm.pbf"
    n = 0
    with osmium.BackReferenceWriter(tmp, ref_src=src, overwrite=True) as w:
        fp = (osmium.FileProcessor(src)
              .with_filter(osmium.filter.KeyFilter("name"))
              .with_filter(osmium.filter.KeyFilter(*CATEGORY_KEYS)))
        for o in fp:
            w.add(o)
            n += 1
    os.replace(tmp, dst)
    print(f"selected {n} objects in {time.time() - t:.0f} s", file=sys.stderr)


def category(tags):
    for k in CATEGORY_KEYS:
        v = tags.get(k)
        if v:
            return k, v
    return None


def extract(selected, out):
    t = time.time()
    # Ways that are members of selected relations: their bounding boxes are needed.
    member_ways = set()
    rel_fp = (osmium.FileProcessor(selected, osmium.osm.RELATION)
              .with_filter(osmium.filter.KeyFilter("name"))
              .with_filter(osmium.filter.KeyFilter(*CATEGORY_KEYS)))
    for r in rel_fp:
        for m in r.members:
            if m.type == "w":
                member_ways.add(m.ref)
    way_bbox = {}
    counts = {"n": 0, "w": 0, "r": 0}
    skipped = 0
    tmp = out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        def emit(kind, oid, lat, lon, tags):
            cat = category(tags)
            name = tags.get("name")
            if cat is None or not name:
                return
            ja = tags.get("name:ja") or ""
            f.write(f"{kind}\t{oid}\t{round(lat * 1e5)}\t{round(lon * 1e5)}\t{cat[0]}\t{clean(cat[1])}"
                    f"\t{clean(name)}\t{clean(ja)}\n")
            counts[kind] += 1

        for o in osmium.FileProcessor(selected).with_locations("flex_mem"):
            if o.is_node():
                if "name" in o.tags and o.location.valid():
                    emit("n", o.id, o.location.lat, o.location.lon, o.tags)
            elif o.is_way():
                lats, lons = [], []
                for nd in o.nodes:
                    if nd.location.valid():
                        lats.append(nd.location.lat)
                        lons.append(nd.location.lon)
                if not lats:
                    skipped += 1
                    continue
                bb = (min(lats), max(lats), min(lons), max(lons))
                if o.id in member_ways:
                    way_bbox[o.id] = bb
                if "name" in o.tags:
                    emit("w", o.id, (bb[0] + bb[1]) / 2, (bb[2] + bb[3]) / 2, o.tags)
            elif o.is_relation():
                if "name" not in o.tags:
                    continue
                bbs = [way_bbox[m.ref] for m in o.members if m.type == "w" and m.ref in way_bbox]
                if not bbs:
                    skipped += 1
                    continue
                lat = (min(b[0] for b in bbs) + max(b[1] for b in bbs)) / 2
                lon = (min(b[2] for b in bbs) + max(b[3] for b in bbs)) / 2
                emit("r", o.id, lat, lon, o.tags)
    os.replace(tmp, out)
    print(f"extracted {counts} (skipped without geometry: {skipped}) in {time.time() - t:.0f} s",
          file=sys.stderr)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, work = sys.argv[1], sys.argv[2]
    os.makedirs(work, exist_ok=True)
    selected = os.path.join(work, "selected.osm.pbf")
    select(src, selected)
    extract(selected, os.path.join(work, "venues.tsv"))


if __name__ == "__main__":
    main()
