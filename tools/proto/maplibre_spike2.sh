#!/bin/bash
# Second emulator session for the MapLibre interception spike: the IOException
# rejection again (the first run was cut short when the emulator's
# system_server restarted), with the full logcat, then the attribution dialog
# tapped at fixed coordinates (uiautomator did not see the map's window on
# this emulator) with emulator screenshots.
#
#   tools/proto/maplibre_spike2.sh <spike apk> <out dir> <step>
#     step: install | io | attr <map> | tap <x> <y> <name> | shot <name>
#
# Run each step while holding the shared emulator lock; emulator-5554 only.
# Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
set -u
apk=${1:?spike apk}
out=${2:?out dir}
step=${3:?step}
shift 3
export ANDROID_SERIAL=emulator-5554
PKG=net.sksat.ashidori.debug
ACT=$PKG/net.sksat.ashidori.SpikeMapActivity
mkdir -p "$out"
a() { adb -s emulator-5554 "$@"; }
shot() { a emu screenrecord screenshot "$out/$1.png" >/dev/null; }

case $step in
  install)
    until [ "$(a shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && a shell pidof com.android.systemui >/dev/null; do sleep 2; done
    if a shell dumpsys activity activities | grep -E "topResumedActivity" | grep -q "$PKG"; then a shell input keyevent KEYCODE_HOME; sleep 2; fi
    a install -r "$apk" ;;
  io)
    a shell am force-stop $PKG
    a shell "run-as $PKG rm -f files/mbgl-offline.db files/mbgl-offline.db-journal"
    a shell "run-as $PKG sh -c 'echo T3 > files/spike-reject'"
    a logcat -c
    a shell am start -W -n $ACT --es map ofm --es mode tiles --es token T3 --es fail io --ef zoom 14 >/dev/null
    sleep 90
    shot reject-io2
    a logcat -d -v time > "$out/reject-io2-full.log"
    a shell "run-as $PKG sh -c 'echo > files/spike-reject'"
    a shell pidof $PKG ;;
  attr)
    a shell am force-stop $PKG
    a logcat -c
    a shell am start -W -n $ACT --es map "${1:-ofm}" --es mode "${2:-tiles}" --es token T1 --ef zoom 14 >/dev/null
    sleep 15
    shot "attr-${1:-ofm}-map" ;;
  tap)
    a shell input tap "$1" "$2"; sleep 3
    shot "$3"
    a logcat -d -v time -s AshiSpike:I ActivityTaskManager:I > "$out/$3.log" ;;
  shot) shot "$1" ;;
esac
