#!/usr/bin/env python3
"""Packs the output of build.sh into the archive the app downloads, and
writes its descriptor (see package.sh).

The archive is a ZIP with venues.aven (stored: its blocks are compressed
already, and the app reads it in place), manifest.json and ATTRIBUTION
(deflated). Entries are sorted and carry a fixed time, so the same input
gives the same bytes.

Standard library only.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

FORMAT = "ashidori venues archive v1"
FILES = ["ATTRIBUTION", "manifest.json", "venues.aven"]
VERSION_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$")
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", required=True, help="output directory of build.sh")
    ap.add_argument("--dist", required=True, help="directory for the archive and its descriptor")
    ap.add_argument("--version", help="version (default: the OpenStreetMap date of the data, YYYYMMDD)")
    a = ap.parse_args()

    missing = [f for f in FILES if not os.path.isfile(os.path.join(a.src, f))]
    if missing:
        sys.exit(f"{a.src}: missing {', '.join(missing)} (run tools/venues/build.sh first)")
    with open(os.path.join(a.src, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    if sha256_file(os.path.join(a.src, "venues.aven")) != manifest.get("sha256"):
        sys.exit("venues.aven does not match manifest.json")

    osm_date = manifest.get("source", {}).get("osmDate", "")
    version = a.version or osm_date[:10].replace("-", "")
    if not VERSION_RE.match(version) or ".." in version:
        sys.exit(f"invalid version {version!r}")
    name = f"ashidori-venues-{version}.zip"
    os.makedirs(a.dist, exist_ok=True)
    path = os.path.join(a.dist, name)

    content = 0
    with zipfile.ZipFile(path + ".part", "w") as z:
        for rel in FILES:
            info = zipfile.ZipInfo(rel, FIXED_TIME)
            info.external_attr = 0o644 << 16
            info.create_system = 3
            stored = rel.endswith(".aven")
            info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
            with open(os.path.join(a.src, rel), "rb") as f:
                data = f.read()
            content += len(data)
            z.writestr(info, data, compress_type=info.compress_type, compresslevel=None if stored else 9)
    os.replace(path + ".part", path)

    descriptor = {
        "format": FORMAT,
        "version": version,
        "file": name,
        "bytes": os.path.getsize(path),
        "sha256": sha256_file(path),
        "osmDate": osm_date,
        "source": manifest.get("source", {}).get("file"),
        "venues": manifest.get("venues"),
        "contentBytes": content,
        "entries": len(FILES),
    }
    desc_path = os.path.join(a.dist, f"ashidori-venues-{version}.json")
    with open(desc_path, "w", encoding="utf-8") as f:
        json.dump(descriptor, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(desc_path)


if __name__ == "__main__":
    main()
