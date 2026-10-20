"""Converts the detail log of an ashidori database (`raw_lines`) into the
tables of docs/design/detail-log-format.md (archive schema 4) as uncompressed
Arrow files, for measurement only.

    python -I extract.py <copy of ashidori.db> <out dir>

Reads a copy of the database; never writes to it. The output holds the
user's data: keep it in a scratch directory and delete it after measuring.

Each row gets `frag`, the 10-minute arrival window it would be finalised in
(the running maximum of raw_lines.time, in rowid order), and `seq`, the
raw_lines rowid (the arrival order; a migrated row keeps its rowid).
"""
import sqlite3, sys, os
import numpy as np, pyarrow as pa, pyarrow.feather as feather

DB, OUT = sys.argv[1], sys.argv[2]
FRAG_MS = 600_000
os.makedirs(OUT, exist_ok=True)

RAW = ("utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos,"
       "DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,"
       "ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,"
       "PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,"
       "AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty,"
       "MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos,"
       "FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos,"
       "CodeType,ChipsetElapsedRealtimeNanos").split(",")
I64, I32, F32, F64, STR, BIN = pa.int64(), pa.int32(), pa.float32(), pa.float64(), pa.string(), pa.binary()

# Final schema (docs/design/detail-log-format.md). Types follow the Android API.
EPOCH = [("time", I64), ("src", I64), ("seq", I64), ("TimeNanos", I64), ("LeapSecond", I32),
         ("TimeUncertaintyNanos", F64), ("FullBiasNanos", I64), ("BiasNanos", F64), ("BiasUncertaintyNanos", F64),
         ("DriftNanosPerSecond", F64), ("DriftUncertaintyNanosPerSecond", F64),
         ("HardwareClockDiscontinuityCount", I32), ("ChipsetElapsedRealtimeNanos", I64)]
SIGNAL = [("time", I64), ("src", I64), ("seq", I64), ("idx", I32), ("Svid", I32), ("ConstellationType", I32),
          ("CarrierFrequencyHz", F32), ("CodeType", STR), ("TimeOffsetNanos", F64), ("State", I32),
          ("ReceivedSvTimeNanos", I64), ("ReceivedSvTimeUncertaintyNanos", I64), ("Cn0DbHz", F64),
          ("BasebandCn0DbHz", F64), ("PseudorangeRateMetersPerSecond", F64),
          ("PseudorangeRateUncertaintyMetersPerSecond", F64), ("AccumulatedDeltaRangeState", I32),
          ("AccumulatedDeltaRangeMeters", F64), ("AccumulatedDeltaRangeUncertaintyMeters", F64),
          ("MultipathIndicator", I32), ("SnrInDb", F64), ("AgcDb", F64),
          ("FullInterSignalBiasNanos", F64), ("FullInterSignalBiasUncertaintyNanos", F64),
          ("SatelliteInterSignalBiasNanos", F64), ("SatelliteInterSignalBiasUncertaintyNanos", F64)]
SAT = [("time", I64), ("src", I64), ("seq", I64), ("SignalCount", I32), ("SignalIndex", I32), ("ConstellationType", I32),
       ("Svid", I32), ("CarrierFrequencyHz", F32), ("Cn0DbHz", F32), ("AzimuthDegrees", F32), ("ElevationDegrees", F32),
       ("UsedInFix", I32), ("HasAlmanacData", I32), ("HasEphemerisData", I32), ("BasebandCn0DbHz", F32)]
NAV = [("time", I64), ("src", I64), ("seq", I64), ("Svid", I32), ("Type", I32), ("Status", I32), ("MessageId", I32),
       ("SubmessageId", I32), ("Data", BIN)]
FIX = [("time", I64), ("src", I64), ("seq", I64), ("Provider", STR), ("LatitudeDegrees", F64), ("LongitudeDegrees", F64),
       ("AltitudeMeters", F64), ("SpeedMps", F32), ("AccuracyMeters", F32), ("BearingDegrees", F32),
       ("SpeedAccuracyMps", F32), ("BearingAccuracyDegrees", F32), ("elapsedRealtimeNanos", I64),
       ("VerticalAccuracyMeters", F32), ("MockLocation", I32)]
NMEA = [("time", I64), ("src", I64), ("seq", I64), ("message", STR)]
IMU1 = [("time", I64), ("ern", I64), ("x", F32), ("y", F32), ("z", F32)]

def num(s, t):
    if s == "": return None
    if t in (I64, I32):
        try: return int(s)
        except ValueError: return int(float(s))
    return float(s)

rows = {k: [] for k in ("gnss_epoch", "gnss_signal", "gnss_sat", "gnss_nav", "gnss_fix", "nmea", "Accel", "Gyro", "Mag")}
frags = {k: [] for k in rows}
SRC = -1  # migrated rows: one source id for the whole of raw_lines
con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
hi = 0; last = 0
epoch_tn = None; epoch_seq = None; epoch_frag = None; idx = 0; prev_raw = False
for rowid, dbtime, line in con.execute("SELECT rowid, time, line FROM raw_lines ORDER BY rowid"):
    hi = max(hi, dbtime); frag = hi // FRAG_MS
    a = line.split(",")
    k = a[0]
    was_raw = prev_raw; prev_raw = k == "Raw"
    if k in ("Accel", "Gyro", "Mag"):
        rows[k].append((int(a[1]), int(a[2]), float(a[3]), float(a[4]), float(a[5]))); frags[k].append(frag)
        last = int(a[1])
    elif k == "Raw":
        f = {n: (a[i + 1] if i + 1 < len(a) else "") for i, n in enumerate(RAW)}
        tn = f["TimeNanos"]
        # An epoch is a run of consecutive Raw rows (rowid order) with the same
        # TimeNanos, as one measurement callback wrote them; its id is the
        # rowid of its first row, and the whole epoch goes to that row's fragment.
        if not was_raw or tn != epoch_tn:
            epoch_tn = tn; epoch_seq = rowid; epoch_frag = frag; idx = 0
            rows["gnss_epoch"].append([int(f["utcTimeMillis"]), SRC, rowid] + [num(f[n], t) for n, t in EPOCH[3:]])
            frags["gnss_epoch"].append(epoch_frag)
        sig = [int(f["utcTimeMillis"]), SRC, epoch_seq, idx]
        for n, t in SIGNAL[4:]:
            sig.append(f[n] or None if t == STR else num(f[n], t))
        rows["gnss_signal"].append(sig); frags["gnss_signal"].append(epoch_frag); idx += 1
        last = int(f["utcTimeMillis"])
    elif k == "Status":
        r = [num(a[1], I64), SRC, rowid] + [num(a[i + 2] if i + 2 < len(a) else "", t) for i, (n, t) in enumerate(SAT[3:])]
        rows["gnss_sat"].append(r); frags["gnss_sat"].append(frag); last = r[0]
    elif k == "Nav":
        data = bytes(int(x) & 0xff for x in a[6:] if x != "")
        rows["gnss_nav"].append([last, SRC, rowid] + [num(a[i], I32) for i in range(1, 6)] + [data]); frags["gnss_nav"].append(frag)
    elif k == "NMEA":
        t = int(a[-1]); last = t
        rows["nmea"].append((t, SRC, rowid, ",".join(a[1:-1]))); frags["nmea"].append(frag)
    elif k == "Fix":
        t = int(a[8]); last = t
        f = lambda i: a[i] if i < len(a) else ""
        rows["gnss_fix"].append([t, SRC, rowid, f(1)] + [num(f(i), t_) for i, t_ in
                                 zip((2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13), (F64, F64, F64, F32, F32, F32, F32, F32, I64, F32, I32))])
        frags["gnss_fix"].append(frag)

schemas = {"gnss_epoch": EPOCH, "gnss_signal": SIGNAL, "gnss_sat": SAT, "gnss_nav": NAV, "gnss_fix": FIX, "nmea": NMEA,
           "Accel": IMU1, "Gyro": IMU1, "Mag": IMU1}
for k, rs in rows.items():
    sch = schemas[k]
    cols = [pa.array([r[i] for r in rs], type=t) for i, (n, t) in enumerate(sch)]
    tbl = pa.table(cols + [pa.array(frags[k], type=I64)], names=[n for n, _ in sch] + ["frag"])
    feather.write_feather(tbl, os.path.join(OUT, f"{k}.arrow"), compression="uncompressed")
    print(k, tbl.num_rows)
