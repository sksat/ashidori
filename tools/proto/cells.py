#!/usr/bin/env python3
"""Zoom-8 cells (Web Mercator tiles) for the region-pack prototype.

  cells.py point <lon> <lat>         cell id and bbox containing the point
  cells.py bbox <z>-<x>-<y> [inset]  bbox of a tile, optionally shrunk by
                                     `inset` degrees on every side
  cells.py region <geojson>          every z8 cell that intersects a
                                     (Multi)Polygon of axis-aligned rectangles

Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
"""
import json
import math
import sys

Z = 8


def lon2x(lon, z):
    return (lon + 180.0) / 360.0 * (1 << z)


def lat2y(lat, z):
    r = math.radians(lat)
    return (1.0 - math.log(math.tan(r) + 1.0 / math.cos(r)) / math.pi) / 2.0 * (1 << z)


def x2lon(x, z):
    return x / (1 << z) * 360.0 - 180.0


def y2lat(y, z):
    n = math.pi - 2.0 * math.pi * y / (1 << z)
    return math.degrees(math.atan(math.sinh(n)))


def bbox(z, x, y, inset=0.0):
    """min_lon,min_lat,max_lon,max_lat of tile z/x/y."""
    return (x2lon(x, z) + inset, y2lat(y + 1, z) + inset, x2lon(x + 1, z) - inset, y2lat(y, z) - inset)


def fmt(b):
    return ",".join(f"{v:.9f}" for v in b)


def region_cells(path):
    g = json.load(open(path))
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    cells = set()
    for poly in polys:
        ring = poly[0]
        lons = [p[0] for p in ring]
        lats = [p[1] for p in ring]
        x0, x1 = int(lon2x(min(lons), Z)), int(lon2x(max(lons), Z))
        y0, y1 = int(lat2y(max(lats), Z)), int(lat2y(min(lats), Z))
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                cells.add((x, y))
    return sorted(cells)


def main(a):
    if not a:
        sys.exit(__doc__)
    if a[0] == "point":
        lon, lat = float(a[1]), float(a[2])
        x, y = int(lon2x(lon, Z)), int(lat2y(lat, Z))
        print(f"{Z}-{x}-{y}", fmt(bbox(Z, x, y)))
    elif a[0] == "bbox":
        z, x, y = map(int, a[1].split("-"))
        print(fmt(bbox(z, x, y, float(a[2]) if len(a) > 2 else 0.0)))
    elif a[0] == "region":
        for x, y in region_cells(a[1]):
            print(f"{Z}-{x}-{y}", fmt(bbox(Z, x, y)))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
