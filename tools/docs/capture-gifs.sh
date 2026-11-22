# The README's GIFs and stills from the recordings in $OUT (sourced by capture-screens.sh
# gifs). The cuts are at the marks the scenes wrote; the waits for the UI (cutNa .. cutNb)
# are left out by mkgif.py, which also gives the syntax of the parts (*N: N times faster).

R=$OUT
gif() { out=$1; shift; python3 -I "$LIB_DIR/mkgif.py" --width 360 "$IMG/$out" "$@"; }
png() { magick "$R/$1.png" -resize 360x -strip -colors 256 -define png:compression-level=9 "PNG8:$IMG/$1.png"; echo "$IMG/$1.png"; }

# A day switched: the tap, the loading sped up, the new day held. The overview is kept to
# about 25 s.
O=$R/overview.webm
O2=$R/overview2.webm
gif overview.gif "$O@map-1.2..back+0.3*1.2" "$O@back+0.3..backed-1.5*6" "$O@backed-1.5..fwd+0.3" \
  "$O@fwd+0.3..fwded-1.5*6" "$O@fwded-1.5..end*1.3" "$O2@feed-0.2..scroll*1.25" "$O2@scroll..end*2"

D=$R/day.webm
gif day.gif "$D@raise-1..prev+0.3" "$D@prev+0.3..end-2*6" "$D@end-2..end"
gif units.gif "$R/units.webm@day-1..week*1.5" "$R/units.webm@week..end*1.3"
gif checkin.gif "$R/checkin.webm@open-0.5..end*1.3"
P=$R/place.webm
gif place.gif "$P@select-0.5..edit" "$P@edit..pick*2" "$P@pick..back*2" "$P@back..venue*3" "$P@venue..end*1.3"
gif mode.gif "$R/mode.webm@start-1..end*1.3"
G=$R/regions.webm
G2=$R/regions2.webm
gif regions.gif "$G@add-0.5..download*2.5" "$G@download..progress" "$G@progress..end*5" "$G2@done-0.5..zoom*3" \
  "$G2@zoom..end*1.4"
for p in map base-maps status status-imu; do png "$p"; done
