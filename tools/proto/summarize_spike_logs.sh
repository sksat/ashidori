#!/bin/sh
# Condenses the logcat of maplibre_spike.sh / maplibre_spike2.sh runs into
# request counts per run, resource kind, token and place of the token
# (tile coordinates and glyph ranges folded into templates).
#
#   tools/proto/summarize_spike_logs.sh <log>...
#
# Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
for f in "$@"; do
  echo "== $(basename "$f" .log)"
  grep -E 'AshiSpike\([ 0-9]+\): (req|reject|resp|event|startActivity)' "$f" |
    sed -E 's/^.*AshiSpike\([ 0-9]+\): //' |
    sed -E 's#/[0-9]+/[0-9]+/[0-9]+\.(pbf|png)#/{z}/{x}/{y}.\1#' |
    sed -E 's#Noto%20Sans%20[A-Za-z]+/[0-9]+-[0-9]+#{fontstack}/{range}#' |
    sed -E 's/ bytes=[^ ]*//; s/ n=[0-9]+//' |
    sort | uniq -c | sort -k2
done
