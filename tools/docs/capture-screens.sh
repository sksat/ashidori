#!/bin/sh
# Records the README's screens (docs/images/) from the debug build on an emulator, with
# simulated data only (the scenario of core/.../sim/Scenarios.scala: University of Tsukuba,
# Tokyo Big Sight, a flight to New Chitose).
#
#   ./gradlew :app:assembleDebug
#   tools/docs/capture-screens.sh all           every scene (three emulator sessions), then gifs
#   tools/docs/capture-screens.sh run SCENE...  one emulator session: set up, then record the scenes
#   tools/docs/capture-screens.sh gifs          the GIFs and stills from the recordings
#
# Scenes (capture-scenes.sh): overview day units checkin place regions mode stills.
# The cuts of each GIF are in capture-gifs.sh.
#
# Every session sets the app up from scratch: it installs the APK, clears the debug app's
# data, seeds the simulation and imports the map and venue data, so whatever else used the
# emulator between sessions does not matter.
#
# Needs: a running Android emulator with a 1080x2400 screen at 420 dpi (a few taps are by
# coordinates), adb, ffmpeg, python3, ImageMagick (magick), curl, and the debug APK. The
# basemap and venue archives of the pinned versions are downloaded once from the GitHub
# releases into $OUT/data; the scene `regions` downloads a region pack (about 120 MB).
#
# Environment: SERIAL (default emulator-5554), OUT (raw recordings and frames, default
# ~/.cache/ashidori-screens; nothing of it goes into the repository), APK (default
# app/build/outputs/apk/debug/app-debug.apk), LOCK (optional): a command prefix that holds
# a lock on the emulator for a session, e.g. LOCK="flock /tmp/emulator.lock".
#
# Recordings and stills go through the emulator console (`adb emu screenrecord`); on the
# emulator these were made with, `adb shell screencap` and `screenrecord` did not work.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LIB_DIR=$ROOT/tools/docs
. "$LIB_DIR/capture-lib.sh"
APK=${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}
IMG=$ROOT/docs/images
mkdir -p "$OUT/data"

# The scenario's home (University of Tsukuba): the emulator's own position must be there
# before recording starts, or its first fix (anywhere) shows as a long flight.
HOME_LON=140.1000
HOME_LAT=36.1110
# The day shown: a weekday commute (Tsukuba - Tokyo Big Sight) whose day before is the
# flight to New Chitose, as MainActivity seeds them (n days back: skipped when n % 9 == 4,
# a flight when n % 23 == 6, weekends out, else the commute).
days() {
  python3 -I - <<'PY'
import datetime as dt
t = dt.date.today()
def kind(n):
    d = t - dt.timedelta(n)
    return "none" if n % 9 == 4 else "flight" if n % 23 == 6 else "weekend" if d.isoweekday() >= 6 else "commute"
c = [n for n in range(1, 40) if kind(n) == "commute"]
n = next((n for n in c if kind(n + 1) == "flight"), next(n for n in c if kind(n + 1) != "none"))
print(t - dt.timedelta(n))
PY
}
DAY=${DAY:-$(days)}

fetch_data() {
  for kind in basemap venues; do
    f=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["file"])' "$ROOT/app/src/main/assets/$kind-pin.json")
    v=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$ROOT/app/src/main/assets/$kind-pin.json")
    [ -s "$OUT/data/$f" ] || curl -fL -o "$OUT/data/$f" "https://github.com/sksat/ashidori/releases/download/$kind-$v/$f"
    echo "$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["sha256"])' "$ROOT/app/src/main/assets/$kind-pin.json")  $OUT/data/$f" | sha256sum -c --quiet
    echo "$kind: $OUT/data/$f"
  done
}

home_fix() {
  for _ in 1 2 3; do a emu geo fix $HOME_LON $HOME_LAT >/dev/null; sleep 2; done
}

setup() {
  fetch_data
  # The system services up (system_server restarts now and then on some emulators).
  n=0
  until sh_ service check activity 2>/dev/null | grep -q ': found' && sh_ service check battery 2>/dev/null | grep -q ': found'; do
    n=$((n + 1)); [ $n -lt 60 ] || { echo "system services not up" >&2; exit 1; }
    sleep 2
  done
  home_fix
  a install -r "$APK" | tail -1
  sh_ pm clear $PKG
  for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION ACTIVITY_RECOGNITION POST_NOTIFICATIONS; do
    sh_ pm grant $PKG android.permission.$p
  done
  sh_ dumpsys deviceidle whitelist +$PKG >/dev/null
  sh_ settings put global enable_back_animation 0
  d=/sdcard/Android/data/$PKG/files
  sh_ mkdir -p $d/basemap $d/venues
  a push "$OUT/data/$(basename "$(ls "$OUT"/data/ashidori-basemap-*.zip)")" $d/basemap/ >/dev/null
  a push "$OUT/data/$(basename "$(ls "$OUT"/data/ashidori-venues-*.zip)")" $d/venues/ >/dev/null
  a logcat -c
  home_fix
  # Onboarded and recording (balanced), at the scenario's home, on battery (when charging,
  # the logger raises the mode and says so).
  sh_ dumpsys battery unplug
  sh_ dumpsys battery set level 100
  dbg LOG --es mode balanced
  sh_ am start -S -W -n $ACT --ez ashidori.sim true --ei ashidori.simDays 40 \
    --ez ashidori.basemapImport true --ez ashidori.venuesImport true >/dev/null
  n=0
  while [ "$(a logcat -d -s ashidori:I | grep -c 'installed from')" -lt 2 ]; do
    n=$((n + 1)); [ $n -lt 90 ] || { echo "data import timed out" >&2; exit 1; }
    sleep 2
  done
  echo "data imported"
  sleep 5
  check_days
}

# Fails when a day of the feed shows an implausible distance (a fix from outside the scenario).
check_days() {
  sh_ am start -S -W -n $ACT >/dev/null
  sleep 3
  tab タイムライン
  for _ in 1 2 3 4 5 6; do
    dump
    python3 -I "$LIB_DIR/uifind.py" "$OUT/ui.xml" --max-km 1000 || { echo "implausible distance on the feed" >&2; exit 1; }
    swipe 540 1900 540 500 300; sleep 1
  done
  echo "days look plausible"
}

start_day() { sh_ am start -S -W -n $ACT --es ashidori.date "$1" >/dev/null; sleep 4; }

. "$LIB_DIR/capture-scenes.sh"

gifs() { . "$LIB_DIR/capture-gifs.sh"; }

# A recording left running by a failed scene is stopped; the status bar and battery reset.
cleanup() { a emu screenrecord stop >/dev/null 2>&1 || true; demo_off; }

session() { if [ -n "${LOCK:-}" ]; then $LOCK sh "$0" "$@"; else sh "$0" "$@"; fi; }

cmd=${1:-}
[ $# -gt 0 ] && shift
case $cmd in
  run)
    trap cleanup EXIT
    setup
    demo_on
    # Each scene in its own process: a failed one is reported and the next one still runs.
    for s in "$@"; do echo "== $s"; sh "$0" scene "$s" || echo "scene $s failed" >&2; done ;;
  scene) "scene_$1" ;;
  gifs) gifs ;;
  all)
    session run overview day units
    session run mode stills checkin place
    session run regions
    gifs ;;
  *) sed -n '2,29p' "$0"; exit 2 ;;
esac
