"""Measures the tables of docs/design/detail-log-format.md (from extract.py)
under one set of conditions: 10-minute arrival fragments, one row group per
fragment, rows ordered inside a fragment as the design says. Prints
aggregate numbers only.

    python -I measure.py <dir from extract.py> <scratch dir for files>

Parquet is written by pyarrow (zstd without a size hint; our writer would
pass the size). "custom" is the best per-column transform (delta,
delta-of-delta, FOR bit-packing, XOR, byte split, ALP) followed by zstd with
the input size known, per column per fragment: an upper bound of what a
purpose-built chunk format reaches on the same rows. It is an estimate: the
sizes of the transformed column streams plus 16 bytes per column, not a
complete lossless format that was written and read back. Times are wall-clock
seconds on the machine that runs this. The gzip level is pinned to 9
(pyarrow's default, the level of the numbers in the design).
"""
import os, sys, time, shutil, collections, json
import numpy as np, pyarrow as pa, pyarrow.feather as feather, pyarrow.parquet as pq, pyarrow.compute as pc
import pyarrow.orc as orc, pyarrow.dataset as ds, zstandard

T_DIR, W = sys.argv[1], sys.argv[2]
def fresh_dir(out, *inputs):
    """Empties `out` for this run. Refuses a symlink, and a target that (after
    resolving links) is, contains or lies inside an input or a protected path
    (DETAIL_BENCH_PROTECT: the database, set by run.sh)."""
    if os.path.islink(out):
        sys.exit(f"refusing to delete {out}: it is a symbolic link")
    real = os.path.realpath(out)
    protect = [p for p in os.environ.get("DETAIL_BENCH_PROTECT", "").split(os.pathsep) if p]
    for i in list(inputs) + protect:
        i = os.path.realpath(i)
        if os.path.commonpath([real, i]) in (real, i):
            sys.exit(f"refusing to delete {real}: it is, contains or lies inside {i}")
    if real in ("/", os.path.realpath(os.path.expanduser("~"))):
        sys.exit(f"refusing to delete {real}")
    shutil.rmtree(real, ignore_errors=True); os.makedirs(real)
fresh_dir(W, T_DIR)
L = {k: feather.read_table(os.path.join(T_DIR, f"{k}.arrow")) for k in
     ("gnss_epoch", "gnss_signal", "gnss_sat", "gnss_nav", "gnss_fix", "nmea", "Accel", "Gyro", "Mag")}
out = collections.OrderedDict()
def say(*a): print(*a, flush=True)

# ---------------------------------------------------------------- IMU layouts
acc, gyr, mag = L["Accel"], L["Gyro"], L["Mag"]
ae, at = acc["ern"].to_numpy(), acc["time"].to_numpy()
ge, gt = gyr["ern"].to_numpy(), gyr["time"].to_numpy()
amap = dict(zip(zip(ae.tolist(), at.tolist()), range(len(ae))))
gi = np.array([amap.get(k, -1) for k in zip(ge.tolist(), gt.tolist())])
same_ern = np.isin(ge, ae).mean()
say(f"gyro rows with an accel row of the same ern: {same_ern:.4f}; same ern and same time: {(gi >= 0).mean():.4f}")
out["gyro_shared_ern"] = round(float(same_ern), 4); out["gyro_shared_ern_time"] = round(float((gi >= 0).mean()), 4)

def wide():
    """imu_ag: one row per (ern, time) of an accel sample, gyro joined when
    both ern and time match; the other gyro samples get rows of their own."""
    n_a = len(ae); rest = np.flatnonzero(gi < 0)
    def col(t, c): return t[c].to_numpy()
    gx = {c: np.full(n_a, np.nan, np.float32) for c in "xyz"}
    gmask = np.zeros(n_a, bool); gmask[gi[gi >= 0]] = True
    for c in "xyz": gx[c][gi[gi >= 0]] = col(gyr, c)[gi >= 0]
    def arr(v, m): return pa.array(v, mask=~m)
    t1 = pa.table({"time": acc["time"], "ern": acc["ern"], "ax": acc["x"], "ay": acc["y"], "az": acc["z"],
                   "gx": arr(gx["x"], gmask), "gy": arr(gx["y"], gmask), "gz": arr(gx["z"], gmask), "frag": acc["frag"]})
    na = len(rest); nulls = pa.nulls(na, pa.float32())
    t2 = pa.table({"time": pa.array(gt[rest]), "ern": pa.array(ge[rest]), "ax": nulls, "ay": nulls, "az": nulls,
                   "gx": pa.array(col(gyr, "x")[rest]), "gy": pa.array(col(gyr, "y")[rest]), "gz": pa.array(col(gyr, "z")[rest]),
                   "frag": pa.array(gyr["frag"].to_numpy()[rest])})
    return pa.concat_tables([t1, t2])

def sort(t, keys): return t.sort_by([("frag", "ascending")] + [(k, "ascending") for k in keys])
SIGKEY = ["ConstellationType", "Svid", "CarrierFrequencyHz", "CodeType", "seq", "idx"]
SATKEY = ["ConstellationType", "Svid", "CarrierFrequencyHz", "seq"]
GNSS = {
    "gnss_epoch": sort(L["gnss_epoch"], ["seq"]),
    "gnss_signal": sort(L["gnss_signal"], SIGKEY),
    "gnss_sat": sort(L["gnss_sat"], SATKEY),
    "gnss_nav": sort(L["gnss_nav"], ["seq"]),
    "gnss_fix": sort(L["gnss_fix"], ["seq"]),
    "nmea": sort(L["nmea"], ["seq"]),
}
LAYOUTS = {
    "separate": {"imu_accel": sort(acc, ["ern", "time"]), "imu_gyro": sort(gyr, ["ern", "time"]), "imu_mag": sort(mag, ["ern", "time"]), **GNSS},
    "shared": {"imu_ag": sort(wide(), ["ern", "time"]), "imu_mag": sort(mag, ["ern", "time"]), **GNSS},
}
ROWS = {k: t.num_rows for k, t in LAYOUTS["separate"].items()} | {"imu_ag": LAYOUTS["shared"]["imu_ag"].num_rows}
say("rows", ROWS)

# ---------------------------------------------------------------- Parquet
def pq_opts(t, codec="zstd", level=3):
    ints = [f.name for f in t.schema if pa.types.is_integer(f.type)]
    strs = [f.name for f in t.schema if pa.types.is_string(f.type) or pa.types.is_binary(f.type)]
    kw = dict(compression=codec, use_dictionary=[c for c in strs if c != "Data" and c != "message"] or False,
              column_encoding={c: "DELTA_BINARY_PACKED" for c in ints}, write_statistics=True)
    if codec in ("zstd", "gzip"): kw["compression_level"] = level
    return kw

def frag_tables(t):
    f = t["frag"].to_numpy(); br = np.flatnonzero(np.diff(f)) + 1
    starts = np.concatenate([[0], br]); ends = np.concatenate([br, [len(f)]])
    for s, e in zip(starts, ends):
        yield int(f[s]), t.slice(s, e - s).drop_columns(["frag"])

def write_parquet(layout, codec="zstd", level=3):
    sizes = {}; day = {}; secs = 0.0
    for name, t in LAYOUTS[layout].items():
        d = os.path.join(W, layout, codec + str(level), name); os.makedirs(d, exist_ok=True)
        tot = 0
        dayp = os.path.join(d, "day.parquet"); dw = None
        for fr, ft in frag_tables(t):
            p = os.path.join(d, f"{fr}.parquet")
            t0 = time.perf_counter(); pq.write_table(ft, p, row_group_size=1 << 30, **pq_opts(ft, codec, level)); secs += time.perf_counter() - t0
            tot += os.path.getsize(p)
            if dw is None: dw = pq.ParquetWriter(dayp, ft.schema, **pq_opts(ft, codec, level))
            dw.write_table(ft, row_group_size=1 << 30)
        dw.close()
        sizes[name] = tot; day[name] = os.path.getsize(dayp)
    return sizes, day, secs

for layout in LAYOUTS:
    for codec, level in (("zstd", 3), ("zstd", 9), ("gzip", 9), ("snappy", 0)):
        if layout == "shared" and codec != "zstd": continue
        s, d, secs = write_parquet(layout, codec, level)
        out[f"parquet {layout} {codec}{level or ''} fragments"] = {k: round(v / 1e6, 3) for k, v in s.items()}
        out[f"parquet {layout} {codec}{level or ''} day"] = {k: round(v / 1e6, 3) for k, v in d.items()}
        say(f"parquet {layout} {codec}{level or ''}: fragments {sum(s.values()) / 1e6:.2f} MB, day files {sum(d.values()) / 1e6:.2f} MB, encode {secs:.1f} s wall-clock")
        say("   ", {k: round(v / 1e6, 2) for k, v in s.items()})

# 10-minute window read with row-group statistics
p = os.path.join(W, "separate", "zstd3", "imu_accel", "day.parquet")
tt = pq.read_table(p, columns=["time"])["time"].to_numpy(); lo = int(np.median(tt)); hi = lo + 600_000
t0 = time.perf_counter(); r = ds.dataset(p).to_table(filter=(ds.field("time") >= lo) & (ds.field("time") < hi)); dt = time.perf_counter() - t0
m = pq.ParquetFile(p).metadata
hit = sum(1 for i in range(m.num_row_groups) if m.row_group(i).column(0).statistics.min < hi and m.row_group(i).column(0).statistics.max >= lo)
say(f"10-min window on imu_accel day file: {hit} of {m.num_row_groups} row groups, {r.num_rows} rows, {dt * 1000:.0f} ms")
out["window_read"] = {"row_groups_hit": hit, "row_groups": m.num_row_groups, "ms": round(dt * 1000)}

# ---------------------------------------------------------------- other formats on the same day tables
def fsize(p): return sum(os.path.getsize(os.path.join(a, f)) for a, _, fs in os.walk(p) for f in fs) if os.path.isdir(p) else os.path.getsize(p)
others = collections.defaultdict(dict)
for name, t in LAYOUTS["separate"].items():
    t = t.drop_columns(["frag"])
    for fmt, fn in (
        ("orc zstd", lambda p: orc.write_table(t, p, compression="zstd")),
        ("arrow ipc zstd", lambda p: feather.write_feather(t, p, compression="zstd")),
    ):
        p = os.path.join(W, f"{name}.{fmt.replace(' ', '_')}")
        try: fn(p); others[fmt][name] = fsize(p)
        except Exception as e: say(fmt, name, "failed", repr(e)[:80])
    try:
        import vortex, vortex.io
        p = os.path.join(W, f"{name}.vortex"); vortex.io.write(vortex.compress(vortex.array(t)), p); others["vortex"][name] = fsize(p)
    except Exception as e: say("vortex", name, "failed", repr(e)[:80])
    try:
        import lance
        p = os.path.join(W, f"{name}.lance"); lance.write_dataset(t, p); others["lance"][name] = fsize(p)
    except Exception as e: say("lance", name, "failed", repr(e)[:80])
for fmt, v in others.items():
    out[f"{fmt} day"] = {k: round(x / 1e6, 3) for k, x in v.items()}
    say(f"{fmt}: {sum(v.values()) / 1e6:.2f} MB", {k: round(x / 1e6, 2) for k, x in v.items()})

# ---------------------------------------------------------------- custom chunks (per column per fragment)
ZC = zstandard.ZstdCompressor(level=3)
def z(b): return len(ZC.compress(b)) if len(b) else 0
def zigzag(v): v = v.astype(np.int64); return ((v << 1) ^ (v >> 63)).astype(np.uint64)
def varint(u):
    u = u.astype(np.uint64); n = len(u)
    if n == 0: return b""
    lens = np.ones(n, np.int64); x = u >> np.uint64(7)
    while x.any(): lens += (x > 0); x >>= np.uint64(7)
    buf = np.zeros(int(lens.sum()), np.uint8); pos = np.concatenate([[0], np.cumsum(lens)[:-1]])
    for k in range(int(lens.max())):
        m = lens > k
        b = ((u[m] >> np.uint64(7 * k)) & np.uint64(0x7f)).astype(np.uint8) | np.where(lens[m] > k + 1, 0x80, 0).astype(np.uint8)
        buf[pos[m] + k] = b
    return buf.tobytes()
def bitpack(v):
    v = v.astype(np.int64); n = len(v)
    if n == 0: return 0
    vv = np.concatenate([v, np.repeat(v[-1:], (-n) % 128)]).reshape(-1, 4, 32)
    adj = vv - vv.min(axis=(1, 2), keepdims=True)
    w = np.array([[int(x).bit_length() for x in row] for row in adj.max(axis=2)])
    return int(w.sum() * 4 + w.size + 8 * len(vv))
def ints(v):
    d = np.diff(v, prepend=0); dd = np.diff(d, prepend=0)
    assert np.array_equal(np.cumsum(np.cumsum(dd)), v)
    return min(z(v.tobytes()), z(varint(zigzag(d))), z(varint(zigzag(dd))), bitpack(d), bitpack(dd))
def floats(x, bits):
    ut = np.uint32 if bits == 32 else np.uint64
    u = x.view(ut); xo = u ^ np.concatenate([[ut(0)], u[:-1]]).astype(ut)
    assert np.array_equal(np.bitwise_xor.accumulate(xo), u)
    def bss(b): return np.ascontiguousarray(b).view(np.uint8).reshape(-1, bits // 8).T.copy().tobytes()
    si = u.astype(np.int64)
    c = [z(u.tobytes()), z(bss(u)), z(bss(xo)), z(varint(zigzag(np.diff(si, prepend=0))))]
    # ALP: decimal exponent per 1024 values, exceptions kept raw
    ks = []; exc = 0
    for i in range(0, len(x), 1024):
        xs = x[i:i + 1024].astype(np.float64)
        for e in range(10):
            k = np.round(xs * 10.0 ** e)
            ok = (k / 10.0 ** e).astype(x.dtype).view(ut) == x[i:i + 1024].view(ut)
            if ok.mean() > 0.97 or e == 9: break
        ks.append(np.where(ok, k, 0).astype(np.int64)); exc += int((~ok).sum())
    k = np.concatenate(ks)
    c.append(z(varint(zigzag(np.diff(k, prepend=0)))) + exc * (bits // 8 + 2))
    return min(c)
def column(a):
    nulls = a.null_count
    head = z(np.packbits(~pc.is_null(a).to_numpy(zero_copy_only=False)).tobytes()) if 0 < nulls < len(a) else 0
    if nulls == len(a): return 1
    a = a.drop_null()
    ty = a.type
    if pa.types.is_integer(ty): return head + ints(a.to_numpy())
    if ty == pa.float32(): return head + floats(a.to_numpy().astype(np.float32), 32)
    if ty == pa.float64(): return head + floats(a.to_numpy().astype(np.float64), 64)
    vals = a.to_pylist()
    raw = b"".join(len(v).to_bytes(2, "little") + (v if isinstance(v, bytes) else v.encode()) for v in vals)
    cnt = collections.Counter(vals)
    if len(cnt) < 256:
        idx = {k: i for i, k in enumerate(cnt)}
        return head + min(z(raw), z(b"\0".join(k if isinstance(k, bytes) else k.encode() for k in cnt)) + z(bytes(idx[v] for v in vals)))
    return head + z(raw)
custom = {}
for layout in LAYOUTS:
    for name, t in LAYOUTS[layout].items():
        if name in custom and layout == "shared": continue
        tot = 0
        for fr, ft in frag_tables(t):
            tot += sum(column(ft[c].combine_chunks()) for c in ft.column_names) + 16 * ft.num_columns
        custom[name] = tot
out["custom fragments"] = {k: round(v / 1e6, 3) for k, v in custom.items()}
say("custom:", {k: round(v / 1e6, 2) for k, v in custom.items()})

# ---------------------------------------------------------------- rows per hour by mode (IMU runs)
e = acc["ern"].to_numpy(); w = acc["time"].to_numpy()
order = np.argsort(e); e, w = e[order], w[order]
br = np.flatnonzero(np.diff(e) > 100_000_000)
runs = []
for s0, s1 in zip(np.concatenate([[0], br + 1]), np.concatenate([br + 1, [len(e)]])):
    if s1 - s0 < 10: continue
    hz = 1e9 / np.median(np.diff(e[s0:s1]))
    runs.append(("max" if hz > 90 else "detail", int(w[s0]), int(w[s1 - 1])))
secs = collections.Counter(); per = collections.defaultdict(collections.Counter)
allt = {k: t["time"].to_numpy() for k, t in LAYOUTS["separate"].items()} | {"imu_ag": LAYOUTS["shared"]["imu_ag"]["time"].to_numpy()}
for mode, t0, t1 in runs:
    secs[mode] += (t1 - t0) / 1000
    for k, tv in allt.items(): per[mode][k] += int(((tv >= t0) & (tv <= t1)).sum())
rate = {m: {k: v / secs[m] * 3600 for k, v in per[m].items()} for m in secs}
out["hours"] = {m: round(s / 3600, 2) for m, s in secs.items()}
out["rows_per_hour"] = {m: {k: round(v) for k, v in r.items()} for m, r in rate.items()}
say("hours", out["hours"]); say("rows/h", out["rows_per_hour"])

def per_hour(sizes):
    res = {}
    for m in rate:
        res[m] = round(sum(rate[m][k] * sizes[k] / ROWS[k] for k in sizes) / 1e6, 2)
    return res
for label in list(out):
    if isinstance(out[label], dict) and label.endswith(("fragments", " day")) and label != "custom fragments":
        tabs = out[label]
        say(f"MB/h {label}: {per_hour({k: v * 1e6 for k, v in tabs.items()})}")
for layout in LAYOUTS:
    tabs = {k: custom[k] for k in LAYOUTS[layout]}
    say(f"MB/h custom {layout}: {per_hour(tabs)}")
json.dump(out, open(os.path.join(W, "..", "measure.json"), "w"), indent=1)
