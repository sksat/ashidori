# The scenes of capture-screens.sh (sourced). Each records $OUT/NAME.webm with marks in
# $OUT/NAME.marks, or stills $OUT/NAME.png; capture-gifs.sh cuts them at the marks.
# Coordinates are for a 1080x2400 screen; texts are found with uiautomator.

SHEET_HANDLE_Y=1840   # the lowered day sheet's handle on the map tab

# The app restarted on tab $1 (a tap right after the start can be lost: retried).
open_tab() {
  sh_ am start -S -W -n $ACT >/dev/null; sleep 4
  tab "$1"
}
tab() {
  for _ in 1 2 3; do
    tap_text "$1"; sleep 2
    dump && grep -q "content-desc=\"$1\"[^>]*selected=\"true\"" "$OUT/ui.xml" && { sleep 1; return 0; }
  done
  echo "tab not selected: $1" >&2; return 1
}
raise_sheet() { swipe 540 $SHEET_HANDLE_Y 540 400 600; }

# Taps the slider of 記録モード under label $1 (the labels sit below the track).
mode_to() {
  dump
  xy=$(find_text "$1")
  # shellcheck disable=SC2086
  set -- $xy
  tap "$1" $(($2 - 80))
}

# 日の表示: the day on the map, the sheet raised, the longest train leg picked (the map
# shows it), then the day before (the flight to New Chitose).
scene_day() {
  start_day "$DAY"; sleep 3
  rec_start day
  sleep 1.5; mark raise
  raise_sheet; sleep 1.5
  dump; xy=$(find_text 電車、 longest); mark leg
  # shellcheck disable=SC2086
  tap $xy; sleep 2.5
  # The day before: the sheet and the map take a few seconds (sped up in the GIF).
  tap_text ‹; mark prev; sleep 8
  mark end
  rec_stop
}

# The label of a day on the feed: 11月18日 (水).
day_label() { python3 -I -c 'import datetime,sys; d=datetime.date.fromisoformat(sys.argv[1]); w="月火水木金土日"[d.weekday()]; print(f"{d.month}月{d.day}日 ({w})")' "$1"; }

# Each unit of the feed loaded and scrolled through once, so that its map cards are drawn
# (and cached) before a recording; then back to 日 at the top.
warm_feed() {
  for u in 週 月 日; do
    tap_text $u
    case $u in 週) wait_text 今週 60 prefix ;; 月) wait_text 今月 60 prefix ;; 日) wait_text 今日 60 prefix ;; esac
    sleep 6
    for _ in 1 2 3 4; do swipe 540 1800 540 700 500; sleep 5; done
    for _ in 1 2 3 4 5 6; do swipe 540 700 540 1900 150; done
    sleep 2
  done
}

# 日・週・月: the feed by day, week and month, each scrolled into the past.
scene_units() {
  open_tab タイムライン
  warm_feed
  rec_start units
  sleep 1.5; mark day
  swipe 540 1800 540 700 700; sleep 1; swipe 540 1800 540 700 700; sleep 1.5
  mark week; tap_wait 週 今週; sleep 1.5
  swipe 540 1800 540 700 700; sleep 1.5
  mark month; tap_wait 月 今月; sleep 1.5
  swipe 540 1800 540 700 700; sleep 2
  mark end
  rec_stop
}

# チェックイン: today on the map, 「チェックイン」, a venue of the venue data near the
# current position (the scenario's home), 「チェックインする」.
scene_checkin() {
  open_tab マップ
  rec_start checkin
  sleep 1.5; mark open
  tap_text チェックイン; sleep 3
  mark pick
  tap_text 鈴屋 || tap_text 丸善; sleep 2.5
  mark commit
  tap_text チェックインする prefix; sleep 3
  mark end
  rec_stop
}

# 場所の修正: a stay of the day, 「✎ 編集」, the venue picked from the list under the
# stay's map; then the venue on the map and its 「訪れた日」.
scene_place() {
  start_day "$DAY"
  raise_sheet; sleep 2
  scroll_to 江東区有明付近 prefix 1900
  rec_start place
  sleep 1; mark select
  tap_text 江東区有明付近 prefix; sleep 2
  mark edit
  tap_text "✎ 編集"; sleep 3
  mark pick
  tap_text 東京ビッグサイト; sleep 3
  mark back
  back; sleep 2.5
  # The venue on the map, where the map's accessibility nodes put it (on emulators that
  # draw no map text, the venue's label is missing but the venue is there).
  dbg CAMERA --ef lat 35.6298 --ef lon 139.7942 --ef zoom 17; sleep 3
  dump; xy=$(find_text 東京ビッグサイト、公民館)
  mark venue
  # shellcheck disable=SC2086
  tap $xy; sleep 3
  tap_text 訪れた日; sleep 3.5
  mark end
  rec_stop
}

# 記録モード: the slider through the modes, back to バランス, then the screen scrolled.
scene_mode() {
  open_tab ステータス
  rec_start mode
  sleep 1.5; mark start
  mode_to 省電力; sleep 1.5
  mode_to 高精度; sleep 1.5
  mode_to 最高精度; sleep 1.5
  mode_to バランス; sleep 1.5
  mark scroll
  swipe 540 1800 540 600 900; sleep 2
  mark end
  rec_stop
}

# Stills: the map (the main screen), the base maps, the status screen and its sensors.
scene_stills() {
  start_day "$DAY"; sleep 4
  still map
  tap_text 地図の種類を変更; sleep 3
  still base-maps
  back; sleep 1
  open_tab ステータス; sleep 3
  still status
  scroll_to 加速度 prefix 1900 || true
  swipe 540 1900 540 1300 600; sleep 3
  still status-imu
}

# 地域データ: 設定 > オフラインのデータ > 「＋ 範囲を選んで取得」, an area around Tsukuba and
# Tokyo, 「ダウンロード」; after the download, the map zoomed in there with the region pack.
scene_regions() {
  open_tab 設定
  tap_text オフラインのデータ; sleep 2
  # The emulator's network is mobile (metered): allow the catalog and packs on it.
  tap_text 自動更新; sleep 2
  tap_text モバイルデータ通信でも取得; sleep 1
  tap_text 閉じる; sleep 2
  rec_start regions
  sleep 1; mark add
  tap_text "＋ 範囲を選んで取得"; sleep 4
  dbg CAMERA --ef lat 35.95 --ef lon 139.95 --ef zoom 8.6; sleep 2
  wait_gone カタログを取得中… 30 || true; sleep 1.5
  mark download
  tap_text ダウンロード; sleep 3
  mark progress
  # The download in progress stays in the recording (sped up in the GIF; the emulator ends a
  # recording at about 60 s): done when the region's row is neither waiting (待機中) nor
  # downloading (取得中 · n%).
  n=0
  until dump_ && [ -z "$(find_text 待機中)" ] && [ -z "$(find_text 取得中 prefix)" ]; do
    n=$((n + 1)); [ $n -lt 60 ] || { echo "region download timed out" >&2; still regions-timeout; return 1; }
    sleep 2
  done
  sleep 1.5
  mark end
  rec_stop
  still regions-done
  rec_start regions2
  sleep 1.5; mark done
  back; sleep 1
  tab マップ
  dbg CAMERA --ef lat 36.0826 --ef lon 140.1114 --ef zoom 12; sleep 3
  mark zoom
  for z in 13 14 15 16; do dbg CAMERA --ef lat 36.0826 --ef lon 140.1114 --ef zoom $z; sleep 1.8; done
  mark end
  rec_stop
}

# The overview at the top of the README, about 20-25 s once cut:
# - the map (the main screen): the day's route, the map panned, the day before and back
#   with the sheet's ‹ ›, the map types;
# - the day sheet with a leg picked (the map shows it), the feed by day, week and month;
# - ステータス: the recording state, the mode changed and back, then scrolled slowly
#   through positioning, satellites and the sensors.
scene_overview() {
  # The status screen once, so that its live views have data.
  open_tab ステータス; sleep 5
  tab タイムライン
  warm_feed
  # The day's card on the feed opens it on the map (without restarting the app).
  scroll_to "$(day_label "$DAY")" prefix 1900 40
  tap_text "$(day_label "$DAY")" prefix; sleep 5
  # Two recordings (the emulator stops one at 60 s): the map, then the feed and ステータス.
  rec_start overview
  sleep 1.2; mark map
  swipe 700 900 520 1150 800; sleep 1.2
  # The day before and back: each day takes a few seconds (sped up in the GIF).
  tap_text ‹; mark back; sleep 7; mark backed
  tap_text ›; mark fwd; sleep 7; mark fwded
  tap_text 地図の種類を変更; sleep 1.8
  back; sleep 0.8
  raise_sheet; sleep 1
  dump; xy=$(find_text 電車、 longest)
  # shellcheck disable=SC2086
  tap $xy; sleep 1.8
  mark end
  rec_stop
  rec_start overview2
  sleep 0.3; mark feed
  tap_text タイムライン; sleep 1.2
  tap_wait 週 今週; sleep 1
  tap_wait 月 今月; sleep 1.2
  tap_text ステータス; sleep 1.5
  mode_to 高精度; sleep 1.2
  mode_to バランス; sleep 1
  mark scroll
  for _ in 1 2 3; do swipe 540 1900 540 1000 1200; sleep 1.2; done
  mark end
  rec_stop
}
