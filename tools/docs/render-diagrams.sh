#!/bin/sh
# Renders every docs/diagrams/*.d2 to docs/diagrams/*.svg (docs/diagrams/README.md).
#
#   tools/docs/render-diagrams.sh          render all diagrams
#   tools/docs/render-diagrams.sh --check  fail if a committed SVG differs from a fresh render
#
# Each SVG embeds a light and a dark theme and follows prefers-color-scheme.
# D2 and the fonts are pinned by version and SHA-256 and cached in
# $ASHIDORI_DOCS_CACHE (default ~/.cache/ashidori-docs). Output is deterministic
# for the same D2 version and fonts, so changing a pin below re-renders every SVG.
#
# Fonts: Noto Sans JP 2.004 (SIL Open Font License 1.1, https://openfontlicense.org),
# the static instances that Google Fonts serves. The default D2 font has no
# Japanese glyphs, so the browser would fall back to a system font. D2 embeds only
# the glyphs a diagram uses, and the OFL allows embedding the subset.
set -eu

D2_VERSION=v0.9.0
D2_SHA256=5669ddc46b99e942cc96078f4a4e36d5e62103348f4c05179ede27802fdd87a9 # d2-v0.9.0-linux-amd64.tar.gz
FONT_URL=https://fonts.gstatic.com/s/notosansjp/v57
FONT_REGULAR=-F6jfjtqLzI2JPCgQBnw7HFyzSD-AsregP8VFBEj75s.ttf
FONT_REGULAR_SHA256=f037d714109f22d57ce75fd9a6b9d23439c903061fb11106c070b2dcc03f2021
FONT_MEDIUM=-F6jfjtqLzI2JPCgQBnw7HFyzSD-AsregP8VFCMj75s.ttf
FONT_MEDIUM_SHA256=204d7384924216356f64dd3a9d17368fffe36d29553120b4d65af0655ba8e148
FONT_BOLD=-F6jfjtqLzI2JPCgQBnw7HFyzSD-AsregP8VFPYk75s.ttf
FONT_BOLD_SHA256=59bb5d2915bba3a256950116e7a48a810bd8cde63793d5111b9e5726eec1e799

# 0: Neutral Default, 200: Dark Mauve.
THEME=0
DARK_THEME=200
LAYOUT=elk

check=false
case "${1:-}" in
  --check) check=true ;;
  "") ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

root=$(cd "$(dirname "$0")/../.." && pwd)
cache=${ASHIDORI_DOCS_CACHE:-$HOME/.cache/ashidori-docs}
mkdir -p "$cache"

# fetch <url> <dest> <sha256>: download unless dest already has the expected hash.
fetch() {
  if [ -f "$2" ] && echo "$3  $2" | sha256sum -c --status; then
    return
  fi
  curl -fsSL -o "$2.part" "$1"
  if ! echo "$3  $2.part" | sha256sum -c --status; then
    echo "error: SHA-256 mismatch for $1" >&2
    rm -f "$2.part"
    exit 1
  fi
  mv "$2.part" "$2"
}

d2dir=$cache/d2-$D2_VERSION
tarball=$d2dir/d2-$D2_VERSION-linux-amd64.tar.gz
d2=$d2dir/d2
mkdir -p "$d2dir"
fetch "https://github.com/terrastruct/d2/releases/download/$D2_VERSION/d2-$D2_VERSION-linux-amd64.tar.gz" \
  "$tarball" "$D2_SHA256"
if [ ! -x "$d2" ] || [ "$d2" -ot "$tarball" ]; then
  tar -xzf "$tarball" -C "$d2dir" --strip-components=2 "d2-$D2_VERSION/bin/d2"
  touch "$d2"
fi

fonts=$cache/fonts
mkdir -p "$fonts"
fetch "$FONT_URL/$FONT_REGULAR" "$fonts/NotoSansJP-Regular.ttf" "$FONT_REGULAR_SHA256"
fetch "$FONT_URL/$FONT_MEDIUM" "$fonts/NotoSansJP-Medium.ttf" "$FONT_MEDIUM_SHA256"
fetch "$FONT_URL/$FONT_BOLD" "$fonts/NotoSansJP-Bold.ttf" "$FONT_BOLD_SHA256"

# render <in.d2> <out.svg>
# --scale 1 writes the natural width and height into the SVG, so GitHub shows a
# small diagram at its size instead of stretching it to the page width.
render() {
  if ! log=$("$d2" --theme "$THEME" --dark-theme "$DARK_THEME" --layout "$LAYOUT" --pad 16 --scale 1 \
    --font-regular "$fonts/NotoSansJP-Regular.ttf" \
    --font-italic "$fonts/NotoSansJP-Regular.ttf" \
    --font-semibold "$fonts/NotoSansJP-Medium.ttf" \
    --font-bold "$fonts/NotoSansJP-Bold.ttf" \
    "$1" "$2" 2>&1); then
    echo "$log" >&2
    exit 1
  fi
}

dir=$root/docs/diagrams
if $check; then
  work=$(mktemp -d "$cache/check.XXXXXX")
  trap 'rm -rf "$work"' EXIT
fi
status=0
for src in "$dir"/*.d2; do
  [ -e "$src" ] || continue
  name=$(basename "$src" .d2)
  if $check; then
    render "$src" "$work/$name.svg"
    if ! cmp -s "$work/$name.svg" "$dir/$name.svg"; then
      echo "docs/diagrams/$name.svg is out of date; run tools/docs/render-diagrams.sh" >&2
      status=1
    fi
  else
    render "$src" "$dir/$name.svg"
    echo "rendered docs/diagrams/$name.svg"
  fi
done
if $check; then
  for svg in "$dir"/*.svg; do
    [ -e "$svg" ] || continue
    if [ ! -e "${svg%.svg}.d2" ]; then
      echo "docs/diagrams/$(basename "$svg") has no .d2 source" >&2
      status=1
    fi
  done
fi
exit $status
