#!/bin/sh
# Downloads the National Police Agency's list of 高度化PICS intersections and
# converts it into the app's bundled list (build.py).
#
#   tools/pics/fetch.sh <work dir> [<output dir>]
#
# Terms: the NPA website's terms of use (https://www.npa.go.jp/rules/index.html)
# allow its content under 公共データ利用規約（第1.0版）（PDL1.0）; the source must
# be credited and the processing stated (tools/ATTRIBUTION). The file name
# carries the list's date: change it (and AS_OF in build.py) for a new list.
set -eu

work=${1:?work dir}
out=${2:-app/src/main/assets/pics}
FILE=${PICS_FILE:-00-260401-zenkoku-kousaten.csv}

mkdir -p "$work"
f="$work/$FILE"
[ -s "$f" ] || curl -fsSL -o "$f" "https://www.npa.go.jp/bureau/traffic/seibi2/annzen-shisetu/utms/$FILE"
python3 "$(dirname "$0")/build.py" "$f" "$out"
