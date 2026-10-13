#!/usr/bin/env python3
"""Packs the output of build.sh into the archive the app downloads, and
writes its descriptor (see package.sh).

The archive is a ZIP with the files at its root. The PMTiles archives are
stored (their tiles are gzip-compressed already, and the app reads them in
place after extraction); the rest is deflated. Entries are sorted and carry a
fixed time, so the same input gives the same bytes.

Standard library only.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

FORMAT = "ashidori basemap archive v1"
REQUIRED = ["style.json", "manifest.json", "ATTRIBUTION", "OFL.txt", "japan.pmtiles", "world.pmtiles"]
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
    ap.add_argument("--version", help="basemap version (default: the Protomaps build in manifest.json)")
    a = ap.parse_args()

    missing = [r for r in REQUIRED if not os.path.isfile(os.path.join(a.src, r))]
    if missing:
        sys.exit(f"{a.src}: missing {', '.join(missing)} (run tools/basemap/build.sh first)")
    with open(os.path.join(a.src, "manifest.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    with open(os.path.join(a.src, "style.json"), encoding="utf-8") as f:
        style = f.read()
    if "asset://" in style or "@BASEMAP_DIR@" not in style:
        sys.exit("style.json does not refer to @BASEMAP_DIR@ only (rebuild with the current assemble.py)")

    version = a.version or manifest["build"]
    if not VERSION_RE.match(version) or ".." in version:
        sys.exit(f"invalid version {version!r}")
    name = f"ashidori-basemap-{version}.zip"
    os.makedirs(a.dist, exist_ok=True)
    path = os.path.join(a.dist, name)

    files = []
    for root, dirs, names in os.walk(a.src):
        dirs.sort()
        for n in names:
            full = os.path.join(root, n)
            files.append(os.path.relpath(full, a.src).replace(os.sep, "/"))
    files.sort()

    content = 0
    with zipfile.ZipFile(path + ".part", "w") as z:
        for rel in files:
            full = os.path.join(a.src, rel)
            info = zipfile.ZipInfo(rel, FIXED_TIME)
            info.external_attr = 0o644 << 16
            info.create_system = 3
            stored = rel.endswith(".pmtiles")
            info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
            with open(full, "rb") as f:
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
        "build": manifest.get("build"),
        "contentBytes": content,
        "entries": len(files),
    }
    desc_path = os.path.join(a.dist, f"ashidori-basemap-{version}.json")
    with open(desc_path, "w", encoding="utf-8") as f:
        json.dump(descriptor, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(desc_path)


if __name__ == "__main__":
    main()
