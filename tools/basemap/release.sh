#!/bin/sh
# Prints the commands that publish a packaged basemap (package.sh); runs
# nothing. Check the archive against the app's pin first.
#
#   tools/basemap/release.sh [<version>] [<dist dir>]
#
# <version> defaults to the one pinned in app/src/main/assets/basemap-pin.json.
# Writes the release notes to <dist>/ashidori-basemap-<version>.notes.md.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
pin="$repo/app/src/main/assets/basemap-pin.json"
field() { python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))[sys.argv[2]])' "$1" "$2"; }

version=${1:-$(field "$pin" version)}
dist=${2:-$here/dist}
zip="$dist/ashidori-basemap-$version.zip"
desc="$dist/ashidori-basemap-$version.json"
[ -f "$zip" ] && [ -f "$desc" ] || { echo "no $zip or $desc (run tools/basemap/package.sh)" >&2; exit 1; }

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
sha=$(sha256 "$zip")
[ "$sha" = "$(field "$desc" sha256)" ] || { echo "$zip does not match $desc" >&2; exit 1; }
if [ "$sha" != "$(field "$pin" sha256)" ]; then
  echo "warning: the app pins another archive ($(field "$pin" version) $(field "$pin" sha256))" >&2
fi
bytes=$(field "$desc" bytes)
build=$(field "$desc" build)

notes="$dist/ashidori-basemap-$version.notes.md"
cat > "$notes" <<EOF
ashidori のオフライン地図のデータ。アプリが初回にダウンロードし、sha256 を照合する。

- 版: $version（Protomaps Basemap $build の日次ビルドから作成）
- ファイル: ashidori-basemap-$version.zip（$bytes バイト）
- sha256: $sha

地図データは © OpenStreetMap contributors であり、Open Database License 1.0
（https://opendatacommons.org/licenses/odbl/1-0/）の下で提供される。出典の一覧は
アーカイブ内の ATTRIBUTION、ライセンスの全文は OFL.txt（フォント）、LICENSE-icons.txt（アイコン）、
LICENSE-basemaps.txt（地図の配色とレイヤー定義）を参照。
EOF

cat <<EOF
# GitHub Releases (the app's default source):
gh release create 'basemap-$version' \\
  '$zip' \\
  '$desc' \\
  --repo sksat/ashidori --title 'オフライン地図 $version' --notes-file '$notes' --latest=false

# Sync server (optional; run in server/, see server/README.md):
pnpm exec wrangler r2 object put 'ashidori-blobs/basemap/$version/ashidori-basemap-$version.zip' \\
  --file '$zip' --content-type application/zip --remote
pnpm exec wrangler r2 object put 'ashidori-blobs/basemap/$version/ashidori-basemap-$version.json' \\
  --file '$desc' --content-type application/json --remote
EOF
