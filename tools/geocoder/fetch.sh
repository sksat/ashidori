#!/bin/sh
# Downloads the inputs of the offline geocoder index and builds it.
#
#   tools/geocoder/fetch.sh <work dir> [<output dir>]
#
# 位置参照情報 (国土交通省): the download service shows 位置参照情報ダウンロード
# サービス利用約款 and 位置参照情報利用約款 before each download, and states that
# using the data means agreeing to them. Read them first:
#   https://nlftp.mlit.go.jp/cgi-bin/isj/dls/_choose_method.cgi
# The files themselves are static: /isj/dls/data/<版>/<都道府県コード>000-<版>.zip.
# GeoNames: CC BY 4.0, https://download.geonames.org/export/dump/readme.txt
set -eu

work=${1:?work dir}
out=${2:-app/src/main/assets/geocoder}
# 大字・町丁目レベル, 令和7年 (2025) data, 第19.0b版. Check the service for newer ones.
ISJ_VERSION=${ISJ_VERSION:-19.0b}
GEONAMES=${GEONAMES:-cities1000}

mkdir -p "$work/isj" "$work/geonames" "$out"
for i in $(seq -w 1 47); do
  f="$work/isj/${i}000-${ISJ_VERSION}.zip"
  [ -s "$f" ] || curl -fsSL -o "$f" "https://nlftp.mlit.go.jp/isj/dls/data/${ISJ_VERSION}/${i}000-${ISJ_VERSION}.zip"
done
g="$work/geonames/${GEONAMES}.zip"
[ -s "$g" ] || curl -fsSL -o "$g" "https://download.geonames.org/export/dump/${GEONAMES}.zip"

python3 "$(dirname "$0")/build_index.py" \
  --isj "$work"/isj/*.zip --geonames "$g" \
  --out "$out/index.bin" --manifest "$out/manifest.json" --attribution "$out/ATTRIBUTION"
