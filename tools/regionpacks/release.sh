#!/bin/sh
# Prints the commands that publish the region packs of a catalog (build.sh)
# on GitHub Releases; runs nothing. Checks first, and fails on any mismatch,
# that every file is the one the catalog lists (size and sha256), that the
# catalog is the one the app pins, and GitHub's limits (1,000 assets per
# release, each under 2 GiB). Run it again on a directory of the published
# assets (`gh release download`) to check them before a pinned APK goes out.
#
#   tools/regionpacks/release.sh [<version>] [<dist dir>]
#
# <version> defaults to the one pinned in app/src/main/assets/regions-pin.json.
# Writes the release notes to <dist>/ashidori-regions-<version>.notes.md.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
pin="$repo/app/src/main/assets/regions-pin.json"
dist=${2:-$here/dist}
if [ -n "${1:-}" ]; then version=$1
elif [ -f "$pin" ]; then version=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$pin")
else echo "give the version (no $pin)" >&2; exit 1
fi
catalog="$dist/ashidori-regions-$version.json"
[ -f "$catalog" ] || { echo "no $catalog (run tools/regionpacks/build.sh)" >&2; exit 1; }

# Checks the files and prints: one asset path per line, then the summary on stderr.
assets=$(python3 -I - "$catalog" "$dist" "$pin" <<'EOF'
import hashlib, json, os, sys
catalog, dist, pin = sys.argv[1:4]
with open(catalog, "rb") as f:
    raw = f.read()
cat = json.loads(raw)
def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()
files = [(s["file"], s["bytes"], s["sha256"]) for s in cat["styles"]] + \
        [(c["map"]["file"], c["map"]["bytes"], c["map"]["sha256"]) for c in cat["cells"]]
for name, n, h in files:
    p = os.path.join(dist, name)
    if not os.path.isfile(p) or os.path.getsize(p) != n or sha(p) != h:
        sys.exit(f"{name} does not match the catalog")
    if n >= 2 * 1024 ** 3:
        sys.exit(f"{name}: 2 GiB or more")
if len(files) + 1 > 1000:
    sys.exit(f"{len(files) + 1} assets (at most 1000)")
if not os.path.isfile(pin):
    sys.exit("the app pins no catalog (run tools/regionpacks/package.sh)")
p = json.load(open(pin))
if p.get("sha256") != hashlib.sha256(raw).hexdigest() or p.get("bytes") != len(raw) or p.get("version") != cat.get("version"):
    sys.exit(f"the app pins another catalog ({p.get('version')} {p.get('sha256')})")
print(os.path.join(dist, os.path.basename(catalog)))
for name, _, _ in files:
    print(os.path.join(dist, name))
total = sum(n for _, n, _ in files)
print(f"{len(cat['cells'])} cells, {total:,} bytes, {len(files) + 1} assets", file=sys.stderr)
EOF
)
cells=$(python3 -I -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["cells"]))' "$catalog")
build=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["build"])' "$catalog")
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

notes="$dist/ashidori-regions-$version.notes.md"
cat > "$notes" <<EOF
ashidori のオフラインの地域データ（ズーム 8 の区画ごとの詳細な地図、ズーム 8〜14）。
アプリは区画を選んで取得し、カタログの大きさと sha256 で照合する。カタログはアプリが sha256 を固定する。

- 版: $version（Protomaps Basemap $build の日次ビルドから作成）
- 区画: $cells
- カタログ: ashidori-regions-$version.json（sha256 $(sha256 "$catalog")）

地図データは © OpenStreetMap contributors であり、Open Database License 1.0
（https://opendatacommons.org/licenses/odbl/1-0/）の下で提供される。
スタイルの束（ashidori-regions-style-*.zip）の出典は束の中の ATTRIBUTION、ライセンスの全文は
OFL.txt（フォント）、LICENSE-icons.txt（アイコン）、LICENSE-basemaps.txt（地図の配色とレイヤー定義）を参照。
EOF

echo "# GitHub Releases: the catalog and the style bundle first, then the packs in batches."
first=1
batch=""
n=0
for f in $assets; do
  if [ $first = 1 ]; then
    first=0
    printf "gh release create 'regions-%s' \\\\\n  '%s'" "$version" "$f"
    continue
  fi
  case "$f" in
    *ashidori-regions-style-*)
      printf " \\\\\n  '%s' \\\\\n  --repo sksat/ashidori --title '地域データ %s' --notes-file '%s' --latest=false\n" "$f" "$version" "$notes"
      continue ;;
  esac
  batch="$batch '$f'"
  n=$((n + 1))
  if [ $n = 40 ]; then
    echo "gh release upload 'regions-$version' --repo sksat/ashidori$batch"
    batch=""
    n=0
  fi
done
[ -z "$batch" ] || echo "gh release upload 'regions-$version' --repo sksat/ashidori$batch"
