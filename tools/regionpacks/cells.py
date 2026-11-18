#!/usr/bin/env python3
"""Zoom-8 cells (Web Mercator tiles, id `8-<x>-<y>`) of the region packs
(docs/design/outbound-and-offline.md 6.4).

  cells.py region <geojson>       every cell intersecting a (Multi)Polygon of
                                  axis-aligned rectangles (tools/basemap/japan.geojson)
  cells.py bbox <cell> [inset]    min_lon,min_lat,max_lon,max_lat of a cell,
                                  shrunk by `inset` degrees on every side

go-pmtiles includes the next tile when the east or south edge of the bbox is
on a tile border, so the packs are extracted with the cell shrunk by INSET
(1e-7 degrees, far below a zoom-14 tile; prototypes 1.2).

Standard library only.
"""
import json
import math
import sys

Z = 8
INSET = 1e-7


def lon2x(lon, z=Z):
    return (lon + 180.0) / 360.0 * (1 << z)


def lat2y(lat, z=Z):
    r = math.radians(lat)
    return (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * (1 << z)


def x2lon(x, z=Z):
    return x / (1 << z) * 360.0 - 180.0


def y2lat(y, z=Z):
    n = math.pi - 2.0 * math.pi * y / (1 << z)
    return math.degrees(math.atan(math.sinh(n)))


def parse(cell):
    z, x, y = (int(v) for v in cell.split("-"))
    if z != Z or not (0 <= x < 1 << Z and 0 <= y < 1 << Z):
        raise ValueError(f"not a zoom-{Z} cell: {cell}")
    return x, y


def name(x, y):
    return f"{Z}-{x}-{y}"


def bbox(x, y, inset=0.0):
    """(min_lon, min_lat, max_lon, max_lat) of cell x/y, shrunk by `inset`."""
    return (x2lon(x) + inset, y2lat(y + 1) + inset, x2lon(x + 1) - inset, y2lat(y) - inset)


def fmt(b):
    return ",".join(f"{v:.9f}" for v in b)


def region(path):
    """Cells intersecting the rectangles of a GeoJSON (Multi)Polygon, sorted by (x, y)."""
    with open(path, encoding="utf-8") as f:
        g = json.load(f)
    if g.get("type") == "Feature":
        g = g["geometry"]
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    cells = set()
    for poly in polys:
        ring = poly[0]
        lons = [p[0] for p in ring]
        lats = [p[1] for p in ring]
        x0, x1 = int(lon2x(min(lons))), int(lon2x(max(lons)))
        y0, y1 = int(lat2y(max(lats))), int(lat2y(min(lats)))
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                cells.add((x, y))
    return sorted(cells)


def sort_key(cell):
    """The catalog's (and the app's download) order: by x, then y."""
    return parse(cell)


def main(a):
    if not a:
        sys.exit(__doc__)
    if a[0] == "region":
        for x, y in region(a[1]):
            print(name(x, y))
    elif a[0] == "bbox":
        x, y = parse(a[1])
        print(fmt(bbox(x, y, float(a[2]) if len(a) > 2 else 0.0)))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
