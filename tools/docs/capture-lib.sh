# Helpers for capture-screens.sh (sourced, not run). POSIX sh plus python3.
#
# Environment:
#   SERIAL  the emulator (default emulator-5554)
#   OUT     the directory for raw recordings and frames (default ~/.cache/ashidori-screens)

SERIAL=${SERIAL:-emulator-5554}
OUT=${OUT:-$HOME/.cache/ashidori-screens}
PKG=net.sksat.ashidori.debug
ACT=$PKG/net.sksat.ashidori.MainActivity
LIB_DIR=${LIB_DIR:-$(cd "$(dirname "$0")" && pwd)}

a() { timeout 120 adb -s "$SERIAL" "$@"; }
sh_() { a shell "$@"; }
dbg() { act=$1; shift; sh_ am broadcast -n "$PKG/net.sksat.ashidori.DebugReceiver" -a "ashidori.debug.$act" "$@" >/dev/null; }

# The UI hierarchy, written to $OUT/ui.xml. A dump takes a few seconds: during a
# recording the time is marked (cutNa .. cutNb) and mkgif.py leaves it out.
REC_ON=0
CUTN=0
dump() {
  if [ "$REC_ON" = 1 ]; then CUTN=$((CUTN + 1)); mark "cut${CUTN}a"; fi
  dump_ || return 1
  if [ "$REC_ON" = 1 ]; then mark "cut${CUTN}b"; fi
}
dump_() {
  for _ in 1 2 3; do
    if sh_ uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && a exec-out cat /sdcard/ui.xml >"$OUT/ui.xml" 2>/dev/null \
      && [ -s "$OUT/ui.xml" ]; then return 0; fi
    sleep 1
  done
  echo "dump failed" >&2; return 1
}

# The centre "x y" of the first node of the last dump whose text or content-desc is $1;
# $2: exact (default), prefix, contains or longest (see uifind.py). Empty when none.
find_text() { python3 -I "$LIB_DIR/uifind.py" "$OUT/ui.xml" "$1" "${2:-exact}"; }

# Taps the node with text $1; fails when it is not on the screen.
tap_text() {
  dump || return 1
  xy=$(find_text "$1" "${2:-exact}")
  if [ -z "$xy" ]; then echo "not found: $1" >&2; return 1; fi
  # shellcheck disable=SC2086
  sh_ input tap $xy
}

# Waits up to $2 s (default 30) for text $1 on the screen ($3: exact or prefix);
# wait_gone: until it is not. During a recording the whole wait is marked as a cut
# (mkgif.py leaves it out).
wait_text() { WANT=1 wait_ui "$@"; }
wait_gone() { WANT=0 wait_ui "$@"; }
wait_ui() {
  n=${2:-30}
  rec=$REC_ON
  if [ "$rec" = 1 ]; then CUTN=$((CUTN + 1)); mark "cut${CUTN}a"; REC_ON=0; fi
  while [ "$n" -gt 0 ]; do
    found=0
    if dump && [ -n "$(find_text "$1" "${3:-exact}")" ]; then found=1; fi
    if [ "$found" = "$WANT" ]; then
      if [ "$rec" = 1 ]; then REC_ON=1; mark "cut${CUTN}b"; fi
      return 0
    fi
    sleep 2; n=$((n - 2))
  done
  REC_ON=$rec
  echo "timed out waiting for: $1" >&2; return 1
}

# tap_wait TEXT AWAITED [gone]: taps TEXT, then waits until AWAITED (a prefix) is on the
# screen (or, with `gone`, is not). In a recording, the tap and the wait are one cut, so
# a loading state in between never shows.
tap_wait() {
  dump || return 1
  xy=$(find_text "$1")
  [ -n "$xy" ] || { echo "not found: $1" >&2; return 1; }
  tw_rec=$REC_ON
  if [ "$tw_rec" = 1 ]; then CUTN=$((CUTN + 1)); tw_cut=$CUTN; mark "cut${tw_cut}a"; REC_ON=0; fi
  # shellcheck disable=SC2086
  tap $xy; sleep 0.3
  if [ "${3:-}" = gone ]; then wait_gone "$2" 20 prefix || true; else wait_text "$2" 20 prefix || true; fi
  if [ "$tw_rec" = 1 ]; then REC_ON=1; mark "cut${tw_cut}b"; fi
}

tap() { sh_ input tap "$1" "$2"; }
swipe() { sh_ input swipe "$1" "$2" "$3" "$4" "${5:-400}"; }
back() { sh_ input keyevent KEYCODE_BACK; }

# The status bar as a fixed clock, a full battery, a full signal and no notifications.
demo_on() {
  sh_ settings put global sysui_demo_allowed 1
  d() { sh_ am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
  sh_ dumpsys battery unplug
  sh_ dumpsys battery set level 100
  d enter
  d clock -e hhmm 1200
  d battery -e level 100 -e plugged false
  d network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
  d notifications -e visible false
}
demo_off() {
  sh_ dumpsys battery reset
  sh_ am broadcast -a com.android.systemui.demo -e command exit >/dev/null
  sh_ settings put global sysui_demo_allowed 0
}

# Recording and stills go through the emulator console (`adb emu screenrecord`),
# which writes on the host. On the emulator this was made with, `screencap`
# aborts (hasReadColorBufferDma) and on-device `screenrecord` writes empty files.
# The emulator ends a recording at about 60 s: a longer scene uses two.
# rec_start NAME [SECONDS]; ... actions ...; rec_stop  ->  $OUT/NAME.webm
# mark LABEL: the time into the recording, to $OUT/NAME.marks (capture-gifs.sh cuts at marks).
rec_start() {
  rm -f "$OUT/$1.webm"
  REC=$1
  : >"$OUT/$1.marks"
  a emu screenrecord start --time-limit "${2:-58}" "$OUT/$1.webm" >/dev/null
  REC_T0=$(date +%s.%N)
  REC_ON=1
  sleep 0.5
}
mark() { echo "$1 $(date +%s.%N)" | awk -v t0="$REC_T0" '{ printf "%s %.2f\n", $1, $2 - t0 }' >>"$OUT/$REC.marks"; }
rec_stop() {
  REC_ON=0
  sleep 0.5
  a emu screenrecord stop >/dev/null
  sleep 1
}

# A still: the screen as PNG to $OUT/$1.png.
still() { a emu screenrecord screenshot "$OUT/$1.png" >/dev/null; }

# Scrolls (swipes up from y $3, default 1700, at most $4 times, default 8) until text $1
# ($2: exact or prefix) is on the screen between y 300 and 2050; fails when it is not.
scroll_to() {
  from=${3:-1700}
  n=${4:-8}
  while [ "$n" -gt 0 ]; do
    dump || return 1
    xy=$(find_text "$1" "${2:-exact}")
    if [ -n "$xy" ] && [ "${xy#* }" -lt 2050 ] && [ "${xy#* }" -gt 300 ]; then return 0; fi
    swipe 540 "$from" 540 $((from - 600)) 500; sleep 1.5
    n=$((n - 1))
  done
  echo "not found: $1" >&2; return 1
}
