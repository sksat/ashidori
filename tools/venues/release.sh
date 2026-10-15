#!/bin/sh
# Prints the command that publishes a packaged venue index (package.sh);
# runs nothing. Checks the archive against the app's pin first.
#
#   tools/venues/release.sh [<version>] [<dist dir>]
#
# <version> defaults to the one pinned in app/src/main/assets/venues-pin.json.
# Writes the release notes to <dist>/ashidori-venues-<version>.notes.md.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
pin="$repo/app/src/main/assets/venues-pin.json"
field() { python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))[sys.argv[2]])' "$1" "$2"; }

version=${1:-$(field "$pin" version)}
dist=${2:-$here/dist}
zip="$dist/ashidori-venues-$version.zip"
desc="$dist/ashidori-venues-$version.json"
[ -f "$zip" ] && [ -f "$desc" ] || { echo "no $zip or $desc (run tools/venues/package.sh)" >&2; exit 1; }

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
sha=$(sha256 "$zip")
[ "$sha" = "$(field "$desc" sha256)" ] || { echo "$zip does not match $desc" >&2; exit 1; }
if [ "$sha" != "$(field "$pin" sha256)" ]; then
  echo "warning: the app pins another archive ($(field "$pin" version) $(field "$pin" sha256))" >&2
fi
bytes=$(field "$desc" bytes)
osm=$(field "$desc" osmDate)
source=$(field "$desc" source)
venues=$(field "$desc" venues)

notes="$dist/ashidori-venues-$version.notes.md"
cat > "$notes" <<EOF
ashidori の施設データ（チェックインの候補を端末内で探す索引）。アプリがダウンロードし、sha256 を照合する。

- 版: $version（OpenStreetMap $osm 時点、Geofabrik の抽出 $source から作成。$venues 件）
- ファイル: ashidori-venues-$version.zip（$bytes バイト）
- sha256: $sha

施設データは © OpenStreetMap contributors であり、OpenStreetMap から作成した Derivative Database
として Open Database License 1.0（https://opendatacommons.org/licenses/odbl/1-0/）の下で提供する。
作り方はリポジトリの tools/venues/、出典はアーカイブ内の ATTRIBUTION を参照。
EOF

cat <<EOF
# GitHub Releases (the app's source):
gh release create 'venues-$version' \\
  '$zip' \\
  '$desc' \\
  --repo sksat/ashidori --title '施設データ $version' --notes-file '$notes' --latest=false
EOF
