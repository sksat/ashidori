#!/bin/bash
# Emulator session for the MapLibre interception spike (branch
# spike/maplibre-intercept). Installs the spike debug APK, runs
# SpikeMapActivity in several modes, saves logcat and screenshots per run,
# then leaves the spike installed (the caller re-installs a main build).
#
#   tools/proto/maplibre_spike.sh <spike apk> <out dir>
#
# Run it under the shared emulator lock. Only emulator-5554 is used.
# On the emulator used (2026-11-18) the uiautomator step of section 2 did
# not find the map window, and the IOException run was cut short by a
# system_server restart: maplibre_spike2.sh redoes both.
# Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
set -u
apk=${1:?spike apk}
out=${2:?out dir}
here=$(cd "$(dirname "$0")" && pwd)
export ANDROID_SERIAL=emulator-5554
PKG=net.sksat.ashidori.debug
ACT=$PKG/net.sksat.ashidori.SpikeMapActivity
mkdir -p "$out"

a() { adb -s emulator-5554 "$@"; }

wait_ready() {
  for _ in $(seq 120); do
    [ "$(a shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && a shell pidof com.android.systemui >/dev/null && return 0
    sleep 2
  done
  echo "emulator not ready" >&2; exit 1
}

not_foreground() {
  if a shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -q "$PKG"; then
    a shell input keyevent KEYCODE_HOME; sleep 2
  fi
}

# adb shell joins its arguments into one command line for the device shell:
# pass each as a single string so that run-as gets the quoting.
clear_cache() { a shell am force-stop $PKG; a shell "run-as $PKG rm -f files/mbgl-offline.db files/mbgl-offline.db-journal files/mbgl-offline.db-wal files/mbgl-offline.db-shm"; }
set_reject() { a shell "run-as $PKG sh -c 'echo $1 > files/spike-reject'"; }

# run <name> <seconds> <am start extras...>
run() {
  local name=$1 secs=$2; shift 2
  a shell am force-stop $PKG
  a logcat -c
  a shell am start -W -n $ACT "$@" > "$out/$name.start" 2>&1
  sleep "$secs"
  a emu screenrecord screenshot "$out/$name.png" >/dev/null  # exec-out screencap failed on this emulator
  a logcat -d -v time -s AshiSpike:I Mbgl:V Mbgl-HttpRequest:V AndroidRuntime:E > "$out/$name.log"
  echo "$name: $(grep -c ' req ' "$out/$name.log") requests, $(grep -c ' reject ' "$out/$name.log") rejected"
}

wait_ready
not_foreground
a install -r "$apk" > "$out/install.txt" 2>&1 || { cat "$out/install.txt"; exit 1; }
a shell wm size > "$out/wm-size.txt"
a shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null

# 1. Which kinds carry the token, per style mode (fresh cache each).
for m in direct url tiles path; do
  set_reject ""; clear_cache
  run "ofm-$m" 25 --es map ofm --es mode $m --es token T1 --ef zoom 14
done
a shell "run-as $PKG ls -la files" > "$out/app-dirs.txt" 2>&1
set_reject ""; clear_cache
run gsi-query 20 --es map gsi --es mode tiles --es token T1 --ef zoom 14

# 2. Attribution links: open the dialog and each entry (ofm tiles, then gsi).
attribution() { # <name>
  local name=$1 i=0
  while :; do
    a shell uiautomator dump /sdcard/ui.xml >/dev/null; a pull /sdcard/ui.xml "$out/$name-ui.xml" >/dev/null
    read -r x y _ < <(python3 -I "$here/ui_nodes.py" "$out/$name-ui.xml" 'Attribution icon')
    [ -n "${x:-}" ] || { echo "no attribution view"; return; }
    a shell input tap "$x" "$y"; sleep 2
    a shell uiautomator dump /sdcard/ui.xml >/dev/null; a pull /sdcard/ui.xml "$out/$name-dialog.xml" >/dev/null
    mapfile -t items < <(python3 -I "$here/ui_nodes.py" "$out/$name-dialog.xml" '.*' --text)
    [ "$i" -lt "${#items[@]}" ] || { a shell input keyevent KEYCODE_BACK; break; }
    printf '%s\n' "${items[@]}" > "$out/$name-items.txt"
    read -r x y _ <<< "${items[$i]}"
    a shell input tap "$x" "$y"; sleep 3
    a shell am start -n $ACT --activity-reorder-to-front >/dev/null 2>&1; sleep 2
    i=$((i + 1)); [ $i -lt 8 ] || break
  done
}
set_reject ""
run ofm-attr 20 --es map ofm --es mode tiles --es token T1 --ef zoom 14
attribution ofm-attr
a logcat -d -v time -s AshiSpike:I > "$out/ofm-attr.log"
run gsi-attr 15 --es map gsi --es mode tiles --es token T1 --ef zoom 14
attribution gsi-attr
a logcat -d -v time -s AshiSpike:I > "$out/gsi-attr.log"
run ofm-direct-attr 20 --es map ofm --es mode direct --es token T1 --ef zoom 14
attribution ofm-direct-attr
a logcat -d -v time -s AshiSpike:I > "$out/ofm-direct-attr.log"

# 3. Cache with a stable token across a restart, then another token, then
#    the stable token with every request rejected (as if offline).
set_reject ""; clear_cache
run cache-1-T1 25 --es map ofm --es mode tiles --es token T1 --ef zoom 14
run cache-2-T1 25 --es map ofm --es mode tiles --es token T1 --ef zoom 14
run cache-3-T2 25 --es map ofm --es mode tiles --es token T2 --ef zoom 14
set_reject "T1"
run cache-4-T1-rejected 25 --es map ofm --es mode tiles --es token T1 --ef zoom 14
set_reject ""; clear_cache
run gcache-1-T1 20 --es map gsi --es mode tiles --es token T1 --ef zoom 14
run gcache-2-T1 20 --es map gsi --es mode tiles --es token T1 --ef zoom 14

# 4. Rejecting a stale token: no crash, no busy retries (fresh cache).
for f in http403 http404 io; do
  set_reject "T3"; clear_cache
  run "reject-$f" 60 --es map ofm --es mode tiles --es token T3 --es fail $f --ef zoom 14
done
#    Mid-session: allowed, then the token turns stale and the map is moved.
set_reject ""; clear_cache
a shell am force-stop $PKG; a logcat -c
a shell am start -W -n $ACT --es map ofm --es mode tiles --es token T4 --ef zoom 14 > /dev/null
sleep 15
set_reject "T4"
a shell log -t AshiSpike -p i MARK_stale
for _ in 1 2 3; do a shell input swipe 900 1200 200 1200 300; sleep 2; done
a shell input tap 540 1200; a shell input tap 540 1200; sleep 30
a emu screenrecord screenshot "$out/reject-mid.png" >/dev/null
a logcat -d -v time -s AshiSpike:I Mbgl:V Mbgl-HttpRequest:V AndroidRuntime:E > "$out/reject-mid.log"
echo "reject-mid: $(grep -c ' reject ' "$out/reject-mid.log") rejected"
a shell am force-stop $PKG
set_reject ""
a shell pidof $PKG; echo "done"
