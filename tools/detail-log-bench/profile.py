"""Profiles the detail log (tables from extract.py): time axis, timestamp
encodings, value structure. Prints aggregate numbers only.

    python -I profile.py <dir from extract.py>
"""
import os, sys, collections
import numpy as np, pyarrow as pa, pyarrow.feather as feather, pyarrow.compute as pc, zstandard

D = sys.argv[1]
T = {k: feather.read_table(os.path.join(D, f"{k}.arrow")) for k in
     ("Accel", "Gyro", "Mag", "gnss_epoch", "gnss_signal", "gnss_sat", "gnss_nav", "nmea")}
Z = zstandard.ZstdCompressor(level=3)
def say(*a): print(*a, flush=True)
def pct(a, ps=(1, 5, 50, 95, 99)): return " ".join(f"p{p}={np.percentile(a, p):.4g}" for p in ps) if len(a) else "-"
def zz(v): v = v.astype(np.int64); return ((v << 1) ^ (v >> 63)).astype(np.uint64)
def varint_bits(v):
    u = zz(v); b = np.ones(len(u)); x = u >> np.uint64(7)
    while x.any(): b += (x > 0); x >>= np.uint64(7)
    return 8 * b.mean() if len(u) else 0
def bitpack_bits(v):
    v = v.astype(np.int64); n = len(v)
    if n == 0: return 0
    vv = np.concatenate([v, np.repeat(v[-1:], (-n) % 128)]).reshape(-1, 4, 32)
    adj = vv - vv.min(axis=(1, 2), keepdims=True)
    w = np.array([[int(x).bit_length() for x in r] for r in adj.max(axis=2)])
    return (w.sum() * 32 + w.size * 8 + 64 * len(vv)) / n
def encodings(name, t):
    """Bits per sample of a timestamp series: min(bit-packing, zigzag varint)."""
    d = np.diff(t, prepend=t[0]); dd = np.diff(d, prepend=0)
    med = np.median(np.diff(t)) if len(t) > 1 else 1
    resid = np.empty_like(t); batch = np.empty_like(t)
    for a in range(0, len(t), 1024):
        s = t[a:a + 1024]; i = np.arange(len(s)); p = (s[-1] - s[0]) / max(1, len(s) - 1)
        resid[a:a + len(s)] = s - (s[0] + np.round(i * p).astype(np.int64))
    for a in range(0, len(t), 32):
        s = t[a:a + 32]; i = np.arange(len(s)); p = (s[-1] - s[0]) / max(1, len(s) - 1)
        batch[a:a + len(s)] = s - (s[0] + np.round(i * p).astype(np.int64))
    r = {k: min(bitpack_bits(v), varint_bits(v)) for k, v in (("delta", d), ("dod", dd), ("period", resid), ("batch", batch))}
    say(f"  bits/sample {name}: " + " ".join(f"{k} {v:.1f}" for k, v in r.items()))

say("== IMU time axis")
runs_all = {}
for s in ("Accel", "Gyro", "Mag"):
    t = T[s]; e = t["ern"].to_numpy(); d = np.diff(e)
    say(f" {s}: n={len(e)} duplicates={int((d == 0).sum())} back-steps={int((d < 0).sum())}")
    br = np.flatnonzero(d > 100_000_000)
    st = np.concatenate([[0], br + 1]); en = np.concatenate([br + 1, [len(e)]])
    rates = collections.Counter(); jit = []
    for a, b in zip(st, en):
        if b - a < 10: continue
        dd = np.diff(e[a:b]); m = np.median(dd); rates[round(1e9 / m, 1)] += (e[b - 1] - e[a]) / 1e9
        jit.append(dd[(dd > 0.5 * m) & (dd < 1.5 * m)] - m)
    jit = np.concatenate(jit); ratio = d / np.median(d)
    w = np.array([np.median(d[i:i + 256]) for i in range(0, len(d) - 256, 256)]); hz = np.round(1e9 / w)
    say(f"   run seconds by rate (Hz): { {k: round(v) for k, v in sorted(rates.items())} }; gaps>100ms {len(br)}")
    say(f"   jitter ns {pct(jit)} std {jit.std():.0f}; dt≈2×period {np.mean((ratio >= 1.5) & (ratio < 2.5)):.4f}; rate changes in 256-sample windows {int((np.abs(np.diff(hz)) > 2).sum())}/{len(hz)}")
    encodings(f"{s} ern", e)
    wall = t["time"].to_numpy(); off = wall - e // 1_000_000; fr = wall // 600_000
    rng = [np.ptp(off[fr == k]) for k in np.unique(fr)]
    say(f"   wall - ern/1e6 range within 10 min (ms): {pct(rng, (50, 95, 100))}")
a = T["Accel"]; g = T["Gyro"]
ka = set(zip(a["ern"].to_numpy().tolist(), a["time"].to_numpy().tolist())); ea = set(a["ern"].to_numpy().tolist())
ge, gt = g["ern"].to_numpy().tolist(), g["time"].to_numpy().tolist()
say(f" gyro samples with an accel sample of the same ern {np.mean([x in ea for x in ge]):.4f}, same ern and time {np.mean([k in ka for k in zip(ge, gt)]):.4f}")
seqs = []
for code, s in enumerate(("Accel", "Gyro", "Mag")):
    seqs.append(np.stack([T[s]["time"].to_numpy(), T[s]["ern"].to_numpy()], 1))
allimu = np.concatenate(seqs)
o = np.argsort(allimu[:, 0], kind="stable");

say("== IMU values")
for s in ("Accel", "Gyro", "Mag"):
    for c in "xyz":
        x = T[s][c].to_numpy().astype(np.float32); u = x.view(np.uint32).astype(np.uint64)
        xo = (u[1:] ^ u[:-1]); nz = xo[xo != 0][:200000]
        lz = np.array([32 - int(v).bit_length() for v in nz]); tz = np.array([(int(v) & -int(v)).bit_length() - 1 for v in nz])
        uq = np.unique(x.astype(np.float64)); gaps = np.diff(uq); vals, cnt = np.unique(np.round(gaps, 9), return_counts=True); lsb = vals[np.argmax(cnt)]
        k = np.round(x.astype(np.float64) / lsb); exact = ((k * lsb).astype(np.float32) == x).mean(); near = (np.abs(x / lsb - k) < 0.01).mean()
        xs = x[:200000].astype(np.float64)
        dec = [((np.round(xs * 10 ** e) / 10 ** e).astype(np.float32) == xs.astype(np.float32)).mean() for e in range(10)]
        bits = 8 * len(Z.compress(x.tobytes())) / len(x)
        say(f" {s}.{c}: repeat {np.mean(xo == 0):.3f} xor-meaningful {np.mean(32 - lz - tz):.1f} bits; modal step {lsb:.6g} exact-on-grid {exact:.4f} within-0.01-step {near:.3f}; decimals ok e=6 {dec[6]:.3f} e=9 {dec[9]:.3f}; zstd3 {bits:.1f} bits")

say("== GNSS")
ep = T["gnss_epoch"]; sig = T["gnss_signal"]
say(f" epochs {ep.num_rows}, signals/epoch {pct(np.bincount(np.unique(sig['seq'].to_numpy(), return_inverse=True)[1]))}")
tn = ep["TimeNanos"].to_numpy(); d = np.diff(tn)
say(f" TimeNanos back-steps {int((d < 0).sum())}; interval jitter (relative) {pct((d[d > 0] - np.median(d)) / np.median(d))}")
encodings("epoch TimeNanos", tn); encodings("epoch utcTimeMillis", ep["time"].to_numpy())
ok = ~pc.is_null(ep["FullBiasNanos"]).to_numpy(zero_copy_only=False)
gps_ms = (tn[ok] - ep["FullBiasNanos"].to_numpy(zero_copy_only=False)[ok].astype(np.int64)) / 1e6
lag = ep["time"].to_numpy()[ok] - (gps_ms + 315964800000 - 1000 * ep["LeapSecond"].to_numpy(zero_copy_only=False)[ok].astype(np.int64))
say(f" callback wall clock - UTC from GNSS time (ms): {pct(lag)}")
c = ep["ChipsetElapsedRealtimeNanos"].to_numpy(zero_copy_only=False)
okc = ~pc.is_null(ep["ChipsetElapsedRealtimeNanos"]).to_numpy(zero_copy_only=False)
say(f" change of ChipsetElapsedRealtimeNanos - TimeNanos per epoch (ns): {pct(np.diff(c[okc].astype(np.int64) - tn[okc]))}")
null = [f.name for f in sig.schema if sig[f.name].null_count == sig.num_rows]
say(f" always-null signal columns: {null}")
for col in [f.name for f in sig.schema if f.type == pa.float64() and f.name not in null]:
    x = sig[col].to_numpy(zero_copy_only=False); x = x[~np.isnan(x)]
    say(f"   {col}: float32-exact {np.mean(x.astype(np.float32).astype(np.float64) == x):.3f}")
a_ = sig["FullInterSignalBiasNanos"].to_numpy(zero_copy_only=False); b_ = sig["SatelliteInterSignalBiasNanos"].to_numpy(zero_copy_only=False)
say(f" FullISB == SatISB {np.mean((a_ == b_) | (np.isnan(a_) & np.isnan(b_))):.4f}")
key = (sig["ConstellationType"].to_numpy().astype(np.int64) * 1000 + sig["Svid"].to_numpy()) * 10**7 + np.nan_to_num(sig["CarrierFrequencyHz"].to_numpy(zero_copy_only=False), nan=0).astype(np.int64) // 100_000
order = np.lexsort((sig["seq"].to_numpy(), key)); ks = key[order]; same = ks[1:] == ks[:-1]
say(f" distinct signals {len(np.unique(key))}")
for col in ("State", "ReceivedSvTimeUncertaintyNanos", "Cn0DbHz", "AccumulatedDeltaRangeState"):
    v = sig[col].to_numpy(zero_copy_only=False).astype(np.float64)[order]
    say(f"   {col}: repeat per signal {np.mean((v[1:] == v[:-1])[same]):.3f}")
st = T["gnss_sat"]
for col in ("Cn0DbHz", "AzimuthDegrees", "ElevationDegrees"):
    x = st[col].to_numpy().astype(np.float32); k = np.round(x.astype(np.float64) * 10)
    say(f"   gnss_sat.{col}: one decimal exact {np.mean((k / 10).astype(np.float32) == x):.3f}")
nav = T["gnss_nav"]
keys = list(zip(nav["Svid"].to_pylist(), nav["Type"].to_pylist(), nav["MessageId"].to_pylist(), nav["SubmessageId"].to_pylist(), nav["Data"].to_pylist()))
seen = set(); dup = 0
for k in keys:
    dup += k in seen; seen.add(k)
say(f" nav: rows {len(keys)}, exact repeats of an earlier row {dup / len(keys):.3f}")
msgs = T["nmea"]["message"].to_pylist(); by = collections.Counter()
for m in msgs: by[m[3:6]] += len(m)
tot = sum(by.values()); blob = "\n".join(msgs).encode()
say(f" nmea byte share { {k: round(v / tot, 3) for k, v in by.most_common(5)} }; zstd3 ratio {len(blob) / len(Z.compress(blob)):.1f}")
nt = T["nmea"]["time"].to_numpy(); say(f" nmea time equal to previous {np.mean(np.diff(nt) == 0):.3f}")

say("== frame length vs zstd3 (IMU: ern deltas, wall residual, raw floats per frame)")
def varint_bytes(u):
    out = bytearray()
    for v in u.tolist():
        while v >= 0x80: out.append((v & 0x7f) | 0x80); v >>= 7
        out.append(v)
    return bytes(out)
for sec in (1, 10, 600):
    tot = 0; raw = 0
    for s in ("Accel", "Gyro", "Mag"):
        t = T[s]; e = t["ern"].to_numpy(); w = t["time"].to_numpy(); X = [t[c].to_numpy().astype(np.float32) for c in "xyz"]
        f = e // (sec * 1_000_000_000); br = np.flatnonzero(np.diff(f)) + 1
        for ix in np.split(np.arange(len(e)), br):
            b = varint_bytes(zz(np.diff(e[ix], prepend=0))) + varint_bytes(zz(np.diff(w[ix] - e[ix] // 1_000_000, prepend=0))) + b"".join(x[ix].tobytes() for x in X)
            raw += len(b); tot += len(Z.compress(b)) + 32
    say(f"  {sec:4d} s frames: {tot / 1e6:.1f} MB (uncompressed {raw / 1e6:.1f} MB)")
